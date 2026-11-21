package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.resolve.SymbolKind

/**
 * DEPRECATION_ERROR: a use of a declaration whose `@Deprecated` level is ERROR (or HIDDEN, for a class), ported
 * from `FirDeprecationChecker` (calls and references) and `FirDeprecatedTypeChecker` (a type naming such a
 * class). The level is read from a project-source declaration's own annotation, by name as the compiler reads
 * this compiler-required annotation's arguments: `level = DeprecationLevel.ERROR`, or the third positional
 * argument. A library declaration's level is not in the index, so its uses stay the DEPRECATION warning.
 *
 * A HIDDEN function is not reported here: resolution does not see it at all, so a call to it is unresolved.
 */
internal object KotlinDeprecationChecks {

    private enum class Level { WARNING, ERROR, HIDDEN }

    /** A call of an ERROR-deprecated function, or a constructor call of an ERROR- or HIDDEN-deprecated class. */
    fun call(checks: KotlinSemanticChecks, call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val symbol = resolver.calleeFunctionOf(call) ?: return null
        val (name, level) = if (symbol.kind == SymbolKind.CONSTRUCTOR) {
            val fqn = resolver.inferType(call)?.qualifiedName ?: return null
            val cls = (checks.service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
            if (cls.name != callee.getReferencedName()) return null
            // The worse of the class's deprecation and its only constructor's.
            val ctor = cls.primaryConstructor?.takeIf { cls.secondaryConstructors.isEmpty() }
            val worst = listOfNotNull(levelOf(cls), ctor?.let(::levelOf)).maxOrNull() ?: return null
            if (worst == Level.HIDDEN && cls.parent !is KtFile) return null
            cls.name to worst
        } else {
            if (!symbol.origin.fromSource) return null
            val fn = (symbol.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return null
            if (fn.name != callee.getReferencedName()) return null
            fn.name to (levelOf(fn)?.takeIf { it == Level.ERROR } ?: return null)
        }
        if (level == Level.WARNING) return null
        return error(callee, name ?: return null)
    }

    /** A type naming an ERROR- or HIDDEN-deprecated project class: `fun f(c: C)`, `class B : C()`. */
    fun type(checks: KotlinSemanticChecks, type: KtUserType, resolver: KotlinResolver): Diagnostic? {
        if (type.qualifier != null || type.parent is KtUserType) return null
        val ref = type.referenceExpression ?: return null
        val name = ref.getReferencedName()
        if (name.isEmpty() || resolver.isTypeParameterInScope(name, type.textRange.startOffset)) return null
        if (inImportOrPackage(type)) return null
        val fqn = checks.service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(type)) ?: return null
        val cls = (checks.service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
        if (cls.name != name) return null
        val level = levelOf(cls) ?: return null
        if (level == Level.WARNING) return null
        // A HIDDEN nested class is invisible to resolution, so a same-named class elsewhere is what the name
        // means (`class C : A() { fun f(b: B) }` beside a hidden `A.B`); only a top-level one is judged.
        if (level == Level.HIDDEN && cls.parent !is KtFile) return null
        return error(ref, name)
    }

    private fun error(at: KtElement, name: String): Diagnostic {
        val r = at.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "'$name' is deprecated", KotlinDiagnosticCodes.DEPRECATION)
    }

    private fun inImportOrPackage(e: KtElement): Boolean {
        var p = e.parent
        while (p != null && p !is KtFile) {
            if (p is KtImportDirective || p is KtPackageDirective) return true
            p = p.parent
        }
        return false
    }

    /** The level of [decl]'s own `@Deprecated`, or null when it has none. */
    private fun levelOf(decl: KtAnnotated): Level? {
        val entry = decl.annotationEntries.firstOrNull { it.shortName?.asString() == "Deprecated" } ?: return null
        val args = entry.valueArguments
        val level = args.firstOrNull { it.getArgumentName()?.asName?.identifier == "level" }
            // A positional third argument is `level`: a named one before it must sit in its own position.
            ?: args.getOrNull(2)?.takeIf { it.getArgumentName() == null }
            ?: return Level.WARNING
        val text = level.getArgumentExpression()?.text?.trim() ?: return Level.WARNING
        // A bare name may be an import alias (`import kotlin.DeprecationLevel.WARNING as ERROR`): the entry it
        // renames decides, which is what the compiler's by-name reading of the argument resolves to.
        val entryName = if ('.' in text) text.substringAfterLast('.')
        else (decl as KtElement).containingKtFile.importDirectives.firstOrNull { it.aliasName == text }
            ?.importedFqName?.asString()?.substringAfterLast('.') ?: text
        return when (entryName) {
            "ERROR" -> Level.ERROR
            "HIDDEN" -> Level.HIDDEN
            else -> Level.WARNING
        }
    }
}
