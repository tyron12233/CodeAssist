package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.resolve.SymbolKind

/**
 * OPERATOR_MODIFIER_REQUIRED: `a + b`, `a < b`, `a in b`, `!a`, `a++` resolved by convention to a function that
 * is not declared `operator`. Ported from call resolution's `CheckOperatorModifier` stage, whose diagnostic
 * `coneDiagnosticToFirDiagnostic` maps to OPERATOR_MODIFIER_REQUIRED.
 *
 * Judged only where the candidates are all known: the receiver is a project-source class, every member of the
 * convention name and arity is a project-source function written without `operator`, and none of them is an
 * `override` (an override is an operator when what it overrides is). Any extension of that name for the
 * receiver could be the operator the call resolves to instead, so its presence backs off.
 */
internal object KotlinOperatorChecks {

    private val BINARY = mapOf(
        KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times", KtTokens.DIV to "div",
        KtTokens.PERC to "rem", KtTokens.RANGE to "rangeTo", KtTokens.RANGE_UNTIL to "rangeUntil",
        KtTokens.LT to "compareTo", KtTokens.GT to "compareTo", KtTokens.LTEQ to "compareTo", KtTokens.GTEQ to "compareTo",
        KtTokens.IN_KEYWORD to "contains", KtTokens.NOT_IN to "contains",
    )

    fun binary(checks: KotlinSemanticChecks, e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        val token = e.operationToken ?: return null
        val name = BINARY[token] ?: return null
        // `a in b` calls `b.contains(a)`.
        val receiver = (if (name == "contains") e.right else e.left) ?: return null
        e.right ?: return null
        if (!requiresModifier(checks, receiver, name, arity = 1, resolver)) return null
        return error(e.operationReference)
    }

    fun prefix(checks: KotlinSemanticChecks, e: KtPrefixExpression, resolver: KotlinResolver): Diagnostic? {
        val (name, arity) = when (e.operationToken) {
            KtTokens.EXCL -> "not" to 0
            KtTokens.MINUS -> "unaryMinus" to 0
            KtTokens.PLUS -> "unaryPlus" to 0
            KtTokens.PLUSPLUS -> "inc" to 0
            KtTokens.MINUSMINUS -> "dec" to 0
            else -> return null
        }
        val operand = e.baseExpression ?: return null
        if (!requiresModifier(checks, operand, name, arity, resolver)) return null
        return error(e.operationReference)
    }

    fun postfix(checks: KotlinSemanticChecks, e: KtPostfixExpression, resolver: KotlinResolver): Diagnostic? {
        val name = when (e.operationToken) {
            KtTokens.PLUSPLUS -> "inc"
            KtTokens.MINUSMINUS -> "dec"
            else -> return null
        }
        val operand = e.baseExpression ?: return null
        if (!requiresModifier(checks, operand, name, 0, resolver)) return null
        return error(e.operationReference)
    }

    private fun error(at: KtElement): Diagnostic {
        val r = at.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'operator' modifier is required on '${at.text}' convention function", KotlinDiagnosticCodes.OPERATOR_MODIFIER_REQUIRED,
        )
    }

    /** Whether every function [receiver]'s type offers for the convention [name] lacks `operator`. */
    private fun requiresModifier(checks: KotlinSemanticChecks, receiver: KtExpression, name: String, arity: Int, resolver: KotlinResolver): Boolean {
        val t = resolver.inferType(receiver) ?: return false
        if (t.isTypeParameter || t.nullable) return false
        val fqn = t.qualifiedName
        val service = checks.service
        if (service.sourceClass(fqn) == null || !service.supertypeClosureKnown(fqn)) return false
        val members = service.ownAndInheritedMembers(fqn).filter { it.name == name }
        // A property of a function type named like the operator is another way to call it.
        if (members.any { it.kind != SymbolKind.METHOD }) return false
        val candidates = members.filter { it.paramTypes.size == arity }
        if (candidates.isEmpty()) return false
        for (c in candidates) {
            if (!c.origin.fromSource) return false
            val fn = (c.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return false
            if (fn.hasModifier(KtTokens.OPERATOR_KEYWORD) || fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
            if (fn.receiverTypeReference != null) return false
        }
        if (service.extensionsFor(fqn, t.typeArguments, namePrefix = name, exactName = true).any { it.name == name }) return false
        return true
    }
}
