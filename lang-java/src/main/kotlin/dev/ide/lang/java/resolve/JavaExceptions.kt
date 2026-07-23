package dev.ide.lang.java.resolve

import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * Checked-exception analysis shared by the unhandled-exception diagnostic
 * ([JavaSemanticDiagnostics]) and its quick-fixes (`analysis/JavaQuickFixes`). Deliberately conservative:
 * it treats a throw as handled (reports/fixes nothing) inside lambdas and field / initializer contexts, and
 * on unresolved or generic exception types — so it never fires on valid code. Must be called under the parse
 * lock (it resolves).
 */
internal object JavaExceptions {

    /** A resolved, non-generic subtype of `Throwable` that is neither a `RuntimeException` nor an `Error`. */
    fun isChecked(type: PsiClassType): Boolean {
        val cls = type.resolve() ?: return false
        if (cls is PsiTypeParameter) return false
        return InheritanceUtil.isInheritor(type, "java.lang.Throwable") &&
            !InheritanceUtil.isInheritor(type, "java.lang.RuntimeException") &&
            !InheritanceUtil.isInheritor(type, "java.lang.Error")
    }

    /**
     * Whether [ex] thrown at [anchor] is caught by an enclosing `try` whose protected region contains the
     * anchor, or declared in the enclosing method's `throws`. Backs off (returns true, so nothing is reported)
     * at a lambda / field / initializer boundary and when no executable boundary is found.
     */
    fun isHandled(anchor: PsiElement, ex: PsiClassType): Boolean {
        var el: PsiElement? = anchor
        while (el != null) {
            when (el) {
                is PsiLambdaExpression -> return true
                is PsiClassInitializer, is PsiField -> return true
                is PsiMethod -> return el.throwsList.referencedTypes.any { TypeConversionUtil.isAssignable(it, ex) }
                is PsiTryStatement -> {
                    val protected = isWithin(anchor, el.tryBlock) || isWithin(anchor, el.resourceList)
                    if (protected && catches(el, ex)) return true
                }
            }
            el = el.parent
        }
        return true
    }

    /** The checked exceptions thrown by the nearest throwing construct at/above [element] that aren't handled
     *  there — used by the quick-fixes to recompute exactly what the diagnostic flagged. A `throw`'s exception
     *  type takes precedence (the diagnostic anchors on the thrown expression, which is often itself a `new`
     *  whose own constructor throws nothing). */
    fun unhandledFor(element: PsiElement): List<PsiClassType> {
        val throwStmt = PsiTreeUtil.getParentOfType(element, PsiThrowStatement::class.java, false)
        if (throwStmt != null && isWithin(element, throwStmt.exception)) {
            return listOfNotNull(throwStmt.exception?.type)
                .filterIsInstance<PsiClassType>().filter { isChecked(it) && !isHandled(throwStmt, it) }
        }
        val throwing = generateSequence(element) { it.parent }.firstOrNull {
            it is PsiNewExpression || it is PsiMethodCallExpression
        } ?: return emptyList()
        val thrown: List<PsiType> = when (throwing) {
            is PsiNewExpression -> throwing.resolveConstructor()?.throwsList?.referencedTypes?.toList() ?: emptyList()
            is PsiMethodCallExpression -> throwing.resolveMethod()?.throwsList?.referencedTypes?.toList() ?: emptyList()
            else -> emptyList()
        }
        return thrown.filterIsInstance<PsiClassType>().filter { isChecked(it) && !isHandled(throwing, it) }
    }

    private fun catches(tryStatement: PsiTryStatement, ex: PsiClassType): Boolean =
        tryStatement.catchBlockParameters.any { param ->
            when (val t = param.type) {
                is PsiDisjunctionType -> t.disjunctions.any { TypeConversionUtil.isAssignable(it, ex) }
                else -> TypeConversionUtil.isAssignable(t, ex)
            }
        }

    private fun isWithin(node: PsiElement, ancestor: PsiElement?): Boolean =
        ancestor != null && PsiTreeUtil.isAncestor(ancestor, node, false)
}
