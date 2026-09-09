package dev.ide.lang.java.rename

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiTypeParameter
import dev.ide.lang.dom.TextRange

/**
 * The symbol a rename is about, distilled into a stable [identityKey] that matches across separately-parsed
 * files. Mirrors `dev.ide.lang.jdt.rename.RenameTarget` (JDT binding key), keyed here off IntelliJ PSI:
 * a type by its qualified name, a method by `owner#name#paramTypes`, a field by `owner#name`, and a file-local
 * (parameter / local / type parameter) by its declaration offset. [isType] drives the constructor special-case
 * (a constructor shares its class's key); [fileLocal] lets the orchestrator skip the project-wide sweep.
 */
data class JavaRenameTarget(
    val oldName: String,
    val kind: String,
    val identityKey: String,
    val fileLocal: Boolean,
    val isType: Boolean,
    val declRange: TextRange,
)

/** Find the renameable symbol under the caret and all of its references within a parsed [PsiJavaFile]. */
object JavaRename {

    /** The renameable symbol whose identifier token contains [offset], or null if the caret isn't on one. */
    fun targetAt(psi: PsiJavaFile, offset: Int): JavaRenameTarget? {
        val len = psi.textLength
        if (len == 0) return null
        val token = psi.findElementAt(offset.coerceIn(0, len - 1)) as? PsiIdentifier ?: return null
        val parent = token.parent
        val element: PsiElement? = when {
            parent is PsiNameIdentifierOwner && parent.nameIdentifier === token -> parent
            parent is PsiJavaCodeReferenceElement -> parent.resolve()
            else -> null
        }
        val elem = element ?: return null
        val key = identityKey(elem) ?: return null
        val range = TextRange(token.textRange.startOffset, token.textRange.endOffset)
        return JavaRenameTarget(
            oldName = (elem as? PsiNamedElement)?.name ?: token.text,
            kind = kindLabel(elem),
            identityKey = key,
            fileLocal = elem is PsiParameter || elem is PsiLocalVariable || elem is PsiTypeParameter,
            isType = elem is PsiClass || (elem is PsiMethod && elem.isConstructor),
            declRange = range,
        )
    }

    /**
     * Every identifier token in [psi] that refers to [target], as source ranges: references (matched by the
     * resolved target's [identityKey]) plus the matching declarations' own name tokens. A constructor shares
     * its class's key, so a type rename also retouches constructor declaration names and `new Foo()` sites.
     */
    fun referencesIn(psi: PsiJavaFile, target: JavaRenameTarget): List<TextRange> {
        val out = ArrayList<TextRange>()
        fun consider(token: PsiElement?, resolved: PsiElement?) {
            if (token == null || resolved == null) return
            if (token.text != target.oldName) return
            if (identityKey(resolved) == target.identityKey) {
                out += TextRange(token.textRange.startOffset, token.textRange.endOffset)
            }
        }
        psi.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                consider(reference.referenceNameElement, reference.resolve())
                super.visitReferenceElement(reference)
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                consider(expression.referenceNameElement, expression.resolve())
                super.visitReferenceExpression(expression)
            }

            override fun visitClass(aClass: PsiClass) {
                consider(aClass.nameIdentifier, aClass)
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                consider(method.nameIdentifier, method)
                super.visitMethod(method)
            }

            override fun visitVariable(variable: com.intellij.psi.PsiVariable) {
                consider(variable.nameIdentifier, variable)
                super.visitVariable(variable)
            }
        })
        return out.distinctBy { it.start }.sortedBy { it.start }
    }

    /** A cross-file-stable identity for [e], or null if it isn't a renameable declaration. */
    private fun identityKey(e: PsiElement): String? = when (e) {
        is PsiClass -> e.qualifiedName
        is PsiMethod ->
            if (e.isConstructor) e.containingClass?.qualifiedName        // shares the class's key
            else e.containingClass?.qualifiedName?.let { "$it#${e.name}#${paramSig(e)}" }
        is PsiField -> e.containingClass?.qualifiedName?.let { "$it#${e.name}" }
        is PsiParameter, is PsiLocalVariable, is PsiTypeParameter ->
            (e as PsiNameIdentifierOwner).nameIdentifier?.let { "local@${it.textRange.startOffset}" }
        else -> null
    }

    private fun paramSig(m: PsiMethod): String =
        m.parameterList.parameters.joinToString(",") { it.type.canonicalText }

    private fun kindLabel(e: PsiElement): String = when (e) {
        is PsiClass -> when {
            e.isAnnotationType -> "annotation"
            e.isEnum -> "enum"
            e.isInterface -> "interface"
            e.isRecord -> "record"
            else -> "class"
        }
        is PsiMethod -> if (e.isConstructor) "class" else "method"
        is PsiField -> "field"
        is PsiParameter -> "parameter"
        is PsiLocalVariable -> "local variable"
        is PsiTypeParameter -> "type parameter"
        else -> "symbol"
    }
}
