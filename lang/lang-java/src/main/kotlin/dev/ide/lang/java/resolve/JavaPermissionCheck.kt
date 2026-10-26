package dev.ide.lang.java.resolve

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiTryStatement
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.resolve.PermissionContext
import dev.ide.lang.resolve.PermissionGuards
import dev.ide.platform.EngineCancellation
import dev.ide.psi.IntellijPsiHost

/**
 * The missing-permission check (Android lint's `MissingPermission`) for Java: a call or `new` of a platform API
 * that needs a permission the manifest does not declare, or a dangerous one nothing around the call checks.
 * The rules are [PermissionGuards]'; this finds the calls and what encloses them.
 */
internal object JavaPermissionCheck {

    fun of(psi: PsiJavaFile, ctx: PermissionContext): List<Diagnostic> = IntellijPsiHost.withParseLock {
        val out = ArrayList<Diagnostic>()
        psi.accept(object : JavaRecursiveElementVisitor() {
            private var seen = 0

            override fun visitElement(element: PsiElement) {
                if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
                super.visitElement(element)
            }

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val anchor = expression.methodExpression.referenceNameElement ?: return
                if (!ctx.table.mayRequire(expression.methodExpression.referenceName ?: return)) return
                val method = expression.resolveMethod() ?: return
                report(anchor, method, ctx, out)
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val reference = expression.classOrAnonymousClassReference ?: return
                if (!ctx.table.mayRequire(reference.referenceName ?: return)) return
                val ctor = expression.resolveConstructor() ?: return
                report(reference, ctor, ctx, out)
            }
        })
        out
    }

    private fun report(anchor: PsiElement, method: PsiMethod, ctx: PermissionContext, out: MutableList<Diagnostic>) {
        val owner = method.containingClass ?: return
        val ownerName = owner.qualifiedName ?: return
        val name = if (method.isConstructor) "<init>" else method.name
        val req = ctx.table.requirement(ownerName, name, method.parameterList.parametersCount) ?: return
        if (exempt(anchor)) return
        val label = if (method.isConstructor) "new ${owner.name}" else "${owner.name}.${method.name}"
        val message = PermissionGuards.check(req, ctx, label) { handled(anchor) } ?: return
        val r = anchor.textRange
        out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, message, code = PermissionGuards.MISSING_PERMISSION)
    }

    /** `@SuppressLint("MissingPermission")` or `@RequiresPermission` on a declaration around [element]. */
    private fun exempt(element: PsiElement): Boolean {
        var node: PsiElement? = element.parent
        while (node != null && node !is PsiFile) {
            if (node is PsiModifierListOwner) {
                for (annotation in node.modifierList?.annotations.orEmpty()) {
                    val name = annotation.nameReferenceElement?.referenceName ?: continue
                    if (PermissionGuards.isRequiresPermission(name)) return true
                    if (PermissionGuards.suppresses(name, annotation.parameterList.text)) return true
                }
            }
            node = node.parent
        }
        return false
    }

    /** A permission check in the enclosing method, or a `try` around the call that catches `SecurityException`. */
    private fun handled(element: PsiElement): Boolean {
        var child: PsiElement = element
        var node: PsiElement? = element.parent
        while (node != null && node !is PsiFile) {
            if (node is PsiTryStatement && child === node.tryBlock &&
                node.catchSections.any { s -> s.catchType?.let { PermissionGuards.catchesSecurityException(it.canonicalText) } == true }
            ) return true
            if (node is PsiMethod || node is PsiField) return PermissionGuards.checksPermission(node.text)
            if (node is PsiClass) return false
            child = node
            node = node.parent
        }
        return false
    }
}
