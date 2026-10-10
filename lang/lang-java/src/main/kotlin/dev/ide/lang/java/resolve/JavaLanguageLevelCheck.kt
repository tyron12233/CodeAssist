package dev.ide.lang.java.resolve

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiCaseLabelElementList
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDeconstructionPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiInstanceOfExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPattern
import com.intellij.psi.PsiSwitchExpression
import com.intellij.psi.PsiSwitchLabeledRuleStatement
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTypeElement
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.platform.EngineCancellation
import dev.ide.psi.IntellijPsiHost

/**
 * Reports source features newer than the module's Java language level, which the build's compiler rejects
 * at that `-source`: `var` (10), lambda-parameter `var` (11), private interface methods (9), switch
 * expressions and `->` labels (14), text blocks (15), records and `instanceof` patterns (16), sealed types
 * (17), and pattern / `null` case labels and record patterns (21).
 *
 * The file is parsed at the highest level, so a newer construct reads as itself rather than as a cascade of
 * syntax errors, and is then reported once, on its keyword or name.
 */
internal object JavaLanguageLevelCheck {

    fun of(psi: PsiJavaFile, level: Int): List<Diagnostic> {
        if (level >= HIGHEST_CHECKED) return emptyList()
        return IntellijPsiHost.withParseLock {
            val out = ArrayList<Diagnostic>()
            fun require(element: PsiElement?, feature: String, since: Int, plural: Boolean = true) {
                if (element == null || level >= since) return
                val r = element.textRange
                val verb = if (plural) "are" else "is"
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "$feature $verb not supported at language level '$level' (requires $since)",
                    code = JavaDiagnosticCodes.LANGUAGE_LEVEL,
                )
            }
            psi.accept(object : JavaRecursiveElementVisitor() {
                private var seen = 0

                override fun visitElement(element: PsiElement) {
                    if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
                    super.visitElement(element)
                }

                override fun visitTypeElement(type: PsiTypeElement) {
                    super.visitTypeElement(type)
                    if (!type.isInferredType || type.text != "var") return
                    val inLambda = (type.parent as? PsiParameter)?.declarationScope is PsiLambdaExpression
                    if (inLambda) require(type, "'var' in lambda parameters", 11, plural = false)
                    else require(type, "Local variable type inference ('var')", 10, plural = false)
                }

                override fun visitMethod(method: PsiMethod) {
                    super.visitMethod(method)
                    if (method.containingClass?.isInterface == true && method.hasModifierProperty(PsiModifier.PRIVATE)) {
                        require(method.nameIdentifier, "Private interface methods", 9)
                    }
                }

                override fun visitSwitchExpression(expression: PsiSwitchExpression) {
                    super.visitSwitchExpression(expression)
                    require(expression.firstChild, "Switch expressions", 14)
                }

                override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                    super.visitSwitchStatement(statement)
                    val rule = statement.body?.statements?.firstOrNull { it is PsiSwitchLabeledRuleStatement }
                    require((rule as? PsiSwitchLabeledRuleStatement)?.firstChild, "Switch rules ('case ->')", 14)
                }

                override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                    super.visitLiteralExpression(expression)
                    if (expression.isTextBlock) require(expression, "Text blocks", 15)
                }

                override fun visitClass(aClass: PsiClass) {
                    super.visitClass(aClass)
                    if (aClass.isRecord) require(aClass.nameIdentifier, "Records", 16)
                }

                override fun visitModifierList(list: PsiModifierList) {
                    super.visitModifierList(list)
                    if (list.hasExplicitModifier(PsiModifier.SEALED) || list.hasExplicitModifier(PsiModifier.NON_SEALED)) {
                        require((list.parent as? PsiClass)?.nameIdentifier, "Sealed classes", 17)
                    }
                }

                override fun visitInstanceOfExpression(expression: PsiInstanceOfExpression) {
                    super.visitInstanceOfExpression(expression)
                    val pattern = expression.pattern ?: return
                    if (pattern is PsiDeconstructionPattern) require(pattern, "Record patterns", 21)
                    else require(pattern, "Patterns in 'instanceof'", 16)
                }

                override fun visitCaseLabelElementList(list: PsiCaseLabelElementList) {
                    super.visitCaseLabelElementList(list)
                    for (element in list.elements) {
                        when {
                            element is PsiPattern -> require(element, "Patterns in switch labels", 21)
                            element is PsiLiteralExpression && element.value == null && element.text == "null" ->
                                require(element, "'case null'", 21, plural = false)
                        }
                    }
                }
            })
            out
        }
    }

    /** The newest feature checked here is Java 21's; at or above it there is nothing to report. */
    private const val HIGHEST_CHECKED = 21
}
