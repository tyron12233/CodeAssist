package dev.ide.lang.java.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter

/**
 * A first-cut lexical scope at a position: enclosing-method parameters + in-scope locals (declared before the
 * caret), then the enclosing type hierarchy's members, then types resolvable by (simple or qualified) name via
 * the [facade]. Enough to drive name-reference resolution; richer scope walking (imports, same-package, static
 * imports, precise shadowing) uses IntelliJ's scope processors and lands with completion in a later step.
 */
class JavaScope(
    private val position: PsiElement?,
    private val offset: Int,
    private val declaringFile: JavaParsedFile?,
    private val facade: JavaPsiFacade,
    private val project: Project,
) : Scope {

    override val enclosing: Scope? = null

    override fun symbols(filter: SymbolFilter): List<Symbol> {
        val out = LinkedHashSet<Symbol>()
        var e: PsiElement? = position
        while (e != null) {
            when (e) {
                is PsiMethod ->
                    e.parameterList.parameters.forEach { out += JavaSymbol(it, declaringFile) }

                is PsiClass -> {
                    e.fields.forEach { out += JavaSymbol(it, declaringFile) }
                    e.methods.forEach { out += JavaSymbol(it, declaringFile) }
                    e.innerClasses.forEach { out += JavaSymbol(it, declaringFile) }
                }

                is PsiCodeBlock ->
                    e.statements.filterIsInstance<PsiDeclarationStatement>().forEach { ds ->
                        ds.declaredElements.filterIsInstance<PsiLocalVariable>().forEach { local ->
                            if (local.textRange.endOffset <= offset) out += JavaSymbol(local, declaringFile)
                        }
                    }
            }
            e = e.parent
        }
        return applyFilter(out.toList(), filter)
    }

    override fun resolve(name: String): ResolveResult {
        symbols().firstOrNull { it.name == name }?.let { return ResolveResult.Resolved(it) }
        // Fall back to a type resolvable by name (simple names resolve against the file's imports/package
        // through the facade's project scope; qualified names resolve directly).
        facade.findClass(name, GlobalSearchScope.allScope(project))
            ?.let { return ResolveResult.Resolved(JavaSymbol(it, declaringFile)) }
        return ResolveResult.Unresolved
    }

    private fun applyFilter(symbols: List<Symbol>, filter: SymbolFilter): List<Symbol> {
        if (filter == SymbolFilter.ALL) return symbols
        val kinds = filter.kinds
        return symbols.filter { s ->
            (kinds == null || s.kind in kinds) &&
                (!filter.staticOnly || dev.ide.lang.resolve.Modifier.STATIC in s.modifiers) &&
                (!filter.instanceOnly || dev.ide.lang.resolve.Modifier.STATIC !in s.modifiers)
        }
    }
}
