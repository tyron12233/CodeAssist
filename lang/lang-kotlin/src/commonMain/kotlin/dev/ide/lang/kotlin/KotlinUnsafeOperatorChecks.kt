package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.pathGuardedNonNullAt
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.resolve.smartCastNonNull

/*
 * A convention operator applied to a nullable value: `-x` and `!b` (UNSAFE_CALL), `x + 1`, `x < 1`, `1 in a`
 * (UNSAFE_OPERATOR_CALL) and `a plus 1` (UNSAFE_INFIX_CALL), where `x: Int?`, `b: Boolean?`, `a: A?`. K2 resolves
 * the operator against the receiver's non-null type (FirCallResolver's unsafe-call candidate, reported by
 * `coneDiagnosticToFirDiagnostic` as one of the three by the call's syntax), and the call fails because only a
 * safe call may reach that member.
 *
 * Judged only where the operator is plainly a MEMBER of the non-null type: an extension of that name in scope
 * (`operator fun A?.times(i: Int)`) may take the nullable receiver, so any extension candidate backs off. The
 * receiver is a name or a qualified path, whose null-guards the flow analysis models.
 */

internal fun KotlinSemanticChecks.unsafeOperatorCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtPrefixExpression::class) { psi ->
        psi as KtPrefixExpression
        KotlinPerf.span("sem.unsafeOperator") {
            if (resolveReady) {
                val name = when (psi.operationToken) {
                    KtTokens.MINUS -> "unaryMinus"
                    KtTokens.PLUS -> "unaryPlus"
                    KtTokens.EXCL -> "not"
                    else -> null
                }
                val operand = psi.baseExpression
                if (name != null && operand != null && unsafeMemberOperator(operand, name, resolver)) {
                    report(unsafe(psi.operationReference, "Only safe (?.) calls are allowed on a nullable receiver of '$name'"))
                }
            }
        }
    },
    KotlinChecker(KtBinaryExpression::class) { psi ->
        psi as KtBinaryExpression
        KotlinPerf.span("sem.unsafeOperator") {
            if (resolveReady) {
                val token = psi.operationToken
                val name = if (token == KtTokens.IDENTIFIER) psi.operationReference.getReferencedName() else BINARY[token]
                val contains = token == KtTokens.IN_KEYWORD || token == KtTokens.NOT_IN
                val receiver = if (contains) psi.right else psi.left
                // A nullable NUMBER on the left of an arithmetic or ordering operator is [operatorOnNullable]'s.
                val skipNumbers = token != KtTokens.IDENTIFIER && !contains
                if (name != null && receiver != null && psi.right != null && unsafeMemberOperator(receiver, name, resolver, skipNumbers)) {
                    val what = if (token == KtTokens.IDENTIFIER) "infix call" else "operator call"
                    report(unsafe(psi.operationReference, "Unsafe $what '$name' on a nullable receiver"))
                }
            }
        }
    },
)

private val BINARY = mapOf(
    KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times", KtTokens.DIV to "div",
    KtTokens.PERC to "rem", KtTokens.RANGE to "rangeTo", KtTokens.RANGE_UNTIL to "rangeUntil",
    KtTokens.LT to "compareTo", KtTokens.GT to "compareTo", KtTokens.LTEQ to "compareTo", KtTokens.GTEQ to "compareTo",
    KtTokens.IN_KEYWORD to "contains", KtTokens.NOT_IN to "contains",
)

/** Whether [receiver] is nullable where it stands and [name] reaches it only as a member of its non-null type. */
private fun KotlinSemanticChecks.unsafeMemberOperator(
    receiver: KtExpression, name: String, resolver: KotlinResolver, skipNumbers: Boolean = false,
): Boolean {
    var r: KtExpression = receiver
    while (r is KtParenthesizedExpression) r = r.expression ?: return false
    // Only a value whose null-guards the flow analysis follows: a name, or a dotted path of names.
    var head: KtExpression = r
    while (head is KtDotQualifiedExpression) {
        if (head.selectorExpression !is KtNameReferenceExpression) return false
        head = head.receiverExpression
    }
    if (head !is KtNameReferenceExpression) return false
    val type = resolver.inferType(r) ?: return false
    if (!type.nullable || type.isTypeParameter) return false
    val nonNull = type.withNullable(false)
    if (skipNumbers && canonicalTypeForCheck(nonNull).qualifiedName in NUMERIC) return false
    if (!service.isKnownType(nonNull.qualifiedName)) return false
    val found = service.membersNamedForCheck(nonNull.qualifiedName, nonNull.typeArguments, name).filter { it.name == name }
    if (found.isEmpty() || found.any { it.isExtension }) return false
    // An extension declared on the nullable type takes the value as it is (`operator fun String?.plus(other:
    // Any?)` makes `s + "x"` fine on a `String?`): a member is not applicable on a nullable receiver, so K2
    // binds the extension instead.
    if (service.extensionsFor(nonNull.qualifiedName, nonNull.typeArguments, name, exactName = true)
            .any { it.name == name && extensionAcceptsNull(it) }
    ) return false
    val at = r.textRange.startOffset
    if (resolver.scopeMemberExtensions(at, nonNull, name, exactName = true).any { it.name == name }) return false
    if (resolver.scopeMemberExtensions(at, type, name, exactName = true).any { it.name == name }) return false
    if (r is KtNameReferenceExpression) {
        if (resolver.smartCastNonNull(r)) return false
        if (hasUnmodeledNullGuard(r, r.getReferencedName())) return false
    } else {
        if (resolver.pathGuardedNonNullAt(r.text, at)) return false
        if (hasUnmodeledNullGuard(r, r.text)) return false
    }
    return true
}

private fun unsafe(at: KtElement, message: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.UNSAFE_NULLABLE)
}
