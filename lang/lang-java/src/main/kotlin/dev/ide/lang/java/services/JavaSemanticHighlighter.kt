package dev.ide.lang.java.services

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.psi.IntellijPsiHost
import dev.ide.vfs.VirtualFile

/**
 * Type-aware Java highlighting: classify each declaration name and each resolved reference by what it truly
 * is (field vs local vs parameter vs method vs type), using IntelliJ resolution — replacing the lexical
 * layer's shape-based guesses.
 */
class JavaSemanticHighlighter(private val psiFor: (VirtualFile) -> PsiJavaFile) : SemanticHighlightService {

    // Under the parse lock (exclusive): `ref.resolve()` below can lazily `buildTree` decompiled classpath PSI,
    // which must not race another parse on ART (same rule as JavaSemanticDiagnostics). Debounced, one file.
    override suspend fun highlight(file: VirtualFile): List<SemanticToken> = IntellijPsiHost.withParseLock {
        val out = ArrayList<SemanticToken>()
        psiFor(file).accept(object : JavaRecursiveElementVisitor() {
            private var seen = 0
            // Poll cancellation while holding the exclusive lock, so a higher-priority pass (completion) preempts.
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (seen++ % 64 == 0) dev.ide.platform.EngineCancellation.checkCanceled()
                super.visitElement(element)
            }

            override fun visitClass(aClass: PsiClass) {
                aClass.nameIdentifier?.let { out += token(it.textRange, aClass, declaration = true) }
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                method.nameIdentifier?.let { out += token(it.textRange, method, declaration = true) }
                super.visitMethod(method)
            }

            override fun visitVariable(variable: com.intellij.psi.PsiVariable) {
                variable.nameIdentifier?.let { out += token(it.textRange, variable, declaration = true) }
                super.visitVariable(variable)
            }

            // Color the leading `@` of an annotation usage; the annotation type NAME is a reference already
            // classified as ANNOTATION below, so `@Foo` reads as one unit (parity with JdtSemanticHighlighter).
            override fun visitAnnotation(annotation: com.intellij.psi.PsiAnnotation) {
                val r = annotation.textRange
                out += SemanticToken(TextRange(r.startOffset, r.startOffset + 1), HighlightKind.ANNOTATION)
                super.visitAnnotation(annotation)
            }

            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                classifyRef(reference)
                super.visitReferenceElement(reference)
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                classifyRef(expression)
                super.visitReferenceExpression(expression)
            }

            private fun classifyRef(ref: PsiJavaCodeReferenceElement) {
                val target = ref.resolve() ?: return
                val nameRange = ref.referenceNameElement?.textRange ?: return
                out += token(nameRange, target, declaration = false)
            }
        })
        out
    }

    private fun token(r: com.intellij.openapi.util.TextRange, target: PsiElement, declaration: Boolean): SemanticToken {
        val kind = kindOf(target)
        val mods = LinkedHashSet<HighlightModifier>()
        if (declaration) mods += HighlightModifier.DECLARATION
        (target as? PsiModifierListOwner)?.let { owner ->
            if (owner.hasModifierProperty(PsiModifier.STATIC)) mods += HighlightModifier.STATIC
            if (owner.hasModifierProperty(PsiModifier.ABSTRACT)) mods += HighlightModifier.ABSTRACT
        }
        // READONLY / MUTABLE describe a BINDING's reassignability — variables only (a `final` method or class
        // is not "readonly" in this sense; IntelliJ underlines a mutable variable and leaves a final one plain).
        if (target is PsiVariable) {
            if (target is PsiEnumConstant || target.hasModifierProperty(PsiModifier.FINAL))
                mods += HighlightModifier.READONLY
            else mods += HighlightModifier.MUTABLE
        }
        if ((target as? com.intellij.psi.PsiDocCommentOwner)?.isDeprecated == true) mods += HighlightModifier.DEPRECATED
        return SemanticToken(TextRange(r.startOffset, r.endOffset), kind, mods)
    }

    private fun kindOf(target: PsiElement): HighlightKind = when (target) {
        is PsiTypeParameter -> HighlightKind.TYPE_PARAMETER
        is PsiClass -> when {
            target.isAnnotationType -> HighlightKind.ANNOTATION
            target.isEnum -> HighlightKind.ENUM
            target.isInterface -> HighlightKind.INTERFACE
            else -> HighlightKind.CLASS
        }
        is PsiMethod -> if (target.isConstructor) HighlightKind.CONSTRUCTOR else HighlightKind.METHOD
        is PsiEnumConstant -> HighlightKind.ENUM_CONSTANT
        is PsiField ->
            if (target.hasModifierProperty(PsiModifier.STATIC) && target.hasModifierProperty(PsiModifier.FINAL))
                HighlightKind.CONSTANT
            else HighlightKind.FIELD
        is PsiParameter -> HighlightKind.PARAMETER
        is PsiLocalVariable -> HighlightKind.LOCAL_VARIABLE
        is PsiPackage -> HighlightKind.NAMESPACE
        else -> HighlightKind.LOCAL_VARIABLE
    }
}
