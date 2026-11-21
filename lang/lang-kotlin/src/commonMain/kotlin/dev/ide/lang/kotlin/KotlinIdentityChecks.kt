package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType

private val PRIMITIVES = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float", "kotlin.Char", "kotlin.Boolean",
)
private val NUMBERS = setOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float")

/** The library value classes the editor's model has no `value` modifier for. */
private val LIBRARY_VALUE_CLASSES = setOf("kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte")

/** The classes a primitive value is boxed to when compared by identity with one (the compiler's
 *  IMPLICIT_BOXING_IN_IDENTITY_EQUALS, a warning): its own nullable type and its supertypes. */
private fun boxingTargets(primitive: String): Set<String> = buildSet {
    add(primitive); add("kotlin.Any"); add("kotlin.Comparable"); add("java.io.Serializable")
    if (primitive in NUMBERS) add("kotlin.Number")
}

/**
 * `a === b` where an operand has no identity: a value class (`value class Foo`, `UInt`), whatever the other
 * side, or a primitive compared with a value of another final type (`1L === 3`, `x === "1"` with `x: Int`). The
 * compiler's FORBIDDEN_IDENTITY_EQUALS. Its milder relatives stay unreported: the same primitive on both sides
 * (DEPRECATED_IDENTITY_EQUALS) and a primitive against one of its supertypes (IMPLICIT_BOXING_IN_IDENTITY_EQUALS)
 * are warnings, and `null` on either side is always allowed. An operand counts only when its type is written down
 * (a literal, or a name declared with an explicit type): an inferred type is the inference's opinion.
 */
internal fun KotlinSemanticChecks.forbiddenIdentityEquals(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken != KtTokens.EQEQEQ && e.operationToken != KtTokens.EXCLEQEQEQ) return null
    val left = e.left ?: return null
    val right = e.right ?: return null
    if (isNullLiteral(left) || isNullLiteral(right)) return null
    fun typeOf(x: KtExpression): KotlinType? =
        if (plainlyTyped(x)) resolver.inferType(x)?.takeIf { !it.isTypeParameter } else null
    val lt = typeOf(left)
    val rt = typeOf(right)
    val forbidden = when {
        lt != null && isValueClass(lt) || rt != null && isValueClass(rt) -> true
        lt == null || rt == null -> false
        lt.qualifiedName !in PRIMITIVES && rt.qualifiedName !in PRIMITIVES -> false
        lt.qualifiedName == rt.qualifiedName -> false
        !lt.nullable && lt.qualifiedName in PRIMITIVES && rt.qualifiedName in boxingTargets(lt.qualifiedName) -> false
        !rt.nullable && rt.qualifiedName in PRIMITIVES && lt.qualifiedName in boxingTargets(rt.qualifiedName) -> false
        // The other side must be a final type that no primitive could also be.
        else -> comparisonFinal(lt) && comparisonFinal(rt)
    }
    if (!forbidden) return null
    val r = e.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Identity equality for these operands is prohibited: one of them has no identity",
        KotlinDiagnosticCodes.FORBIDDEN_IDENTITY_EQUALS,
    )
}

private fun KotlinSemanticChecks.isValueClass(t: KotlinType): Boolean {
    if (t.qualifiedName in LIBRARY_VALUE_CLASSES) return true
    val cls = (service.sourceClass(t.qualifiedName)?.node as? KotlinDomNode)?.psi as? KtClass ?: return false
    return cls.isValue() || cls.hasModifier(KtTokens.INLINE_KEYWORD)
}
