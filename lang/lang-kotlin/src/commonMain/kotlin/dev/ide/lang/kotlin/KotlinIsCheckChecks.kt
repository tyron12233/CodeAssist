package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.flowProvenTypes
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * `a is B` that can never hold because no value is both an `A` and a `B`: the types are unrelated and one is
 * final, or both are classes (a class has one superclass chain, so two unrelated ones never meet). The
 * compiler's IMPOSSIBLE_IS_CHECK_ERROR. Judged only on the operand's DECLARED type (a literal, or a name written
 * with its type and not smart-cast since: the compiler reports a smart-cast type's impossibility as a warning
 * only), and only where both hierarchies are completely known (project source or the compiled-in builtins).
 * A nullable target (`a is B?`) holds for `null`: with a nullable operand the check then relies on `null` alone
 * (IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR, FirCastOperatorsChecker), with a non-null one it is impossible still.
 */
internal fun KotlinSemanticChecks.impossibleIsCheck(e: KtIsExpression, resolver: KotlinResolver): Diagnostic? {
    val typeRef = e.typeReference ?: return null
    val written = typeRef.text.trim()
    val targetNullable = written.endsWith("?")
    val text = written.removeSuffix("?").trim()
    if (text.endsWith("?") || '<' in text || text.startsWith("(")) return null
    val left = e.leftHandSide ?: return null
    if (!plainlyTyped(left)) return null
    (unwrapParen(left) as? KtNameReferenceExpression)?.let { if (resolver.flowProvenTypes(it, deep = true).isNotEmpty()) return null }
    val target = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(typeRef)) ?: return null
    val operand = resolver.inferType(left) ?: return null
    if (operand.isTypeParameter || target.isTypeParameter) return null
    val o = shapeOf(operand) ?: return null
    val t = shapeOf(target) ?: return null
    if (operand.qualifiedName == target.qualifiedName) return null
    if (inheritsFrom(operand.qualifiedName, target.qualifiedName) != false) return null
    if (inheritsFrom(target.qualifiedName, operand.qualifiedName) != false) return null
    if (!(o.isFinal || t.isFinal || (o.isClass && t.isClass))) return null
    val r = e.textRange
    if (targetNullable && operand.nullable) return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Incompatible types '${renderType(operand)}' and '${renderType(target)}?': the check is '${!e.isNegated}' only for null",
        KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL,
    )
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Incompatible types '${renderType(operand)}' and '${renderType(target)}': the check is always '${e.isNegated}'",
        KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK,
    )
}

private class Shape(val isFinal: Boolean, val isClass: Boolean)

/** Whether [t] is final and whether it is a class (not an interface), when its hierarchy is completely known:
 *  a project-source class or one of the builtin final value types. */
private fun KotlinSemanticChecks.shapeOf(t: KotlinType): Shape? {
    if (t.qualifiedName in FINAL_VALUE_TYPES) return Shape(isFinal = true, isClass = true)
    if (!service.supertypeClosureKnown(t.qualifiedName)) return null
    // A classpath class: its class file says whether it is an interface and whether it is final.
    if (service.sourceClass(t.qualifiedName) == null) {
        val iface = service.isInterfaceType(t.qualifiedName) ?: return null
        val final = service.isFinalType(t.qualifiedName) ?: return null
        return Shape(isFinal = final && !iface, isClass = !iface)
    }
    val decl = (service.sourceClass(t.qualifiedName)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
    if (decl is KtObjectDeclaration) return Shape(isFinal = true, isClass = true)
    val cls = decl as? KtClass ?: return null
    if (cls.isInterface()) return Shape(isFinal = false, isClass = false)
    val open = cls.hasModifier(KtTokens.OPEN_KEYWORD) || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.isSealed() ||
        cls.hasModifier(KtTokens.EXPECT_KEYWORD)
    return Shape(isFinal = !open, isClass = true)
}
