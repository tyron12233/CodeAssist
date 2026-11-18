package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * Errors that can be read off the tree alone: a jump with nowhere to go, a label that names nothing, `this`
 * where there is no receiver, an `if` used for a value it may not produce, a `data class` with nothing in it.
 *
 * None of these asks the symbol model anything, so none can be wrong about a type it failed to infer. Each
 * still backs off where the tree does not settle the question (a lambda may or may not have a receiver; a
 * lambda passed to something other than a plain call has no label this file can name).
 */
internal object KotlinStructuralChecks {

    /** `break` / `continue` outside any loop, or with a label that no enclosing loop carries. */
    fun jumpOutsideLoop(jump: KtExpressionWithLabel): Diagnostic? {
        val label = jump.getLabelName()
        val word = if (jump is KtBreakExpression) "break" else "continue"
        var sawLoop = false
        var node = jump.parent
        while (node != null) {
            when (node) {
                is KtLoopExpression -> {
                    // Only the BODY is inside the loop: a jump in `for (x in <here>)` belongs to whatever
                    // encloses the loop.
                    val body = node.body
                    if (body != null && contains(body, jump)) {
                        sawLoop = true
                        if (label == null) return null
                        if ((node.parent as? KtLabeledExpression)?.getLabelName() == label) return null
                    }
                }
                // A function, accessor or class body is where a jump stops: nothing outside it is reachable.
                is KtNamedFunction, is KtPropertyAccessor, is KtClassOrObject, is KtFile -> break
                else -> {}
            }
            node = node.parent
        }
        val r = jump.textRange
        return if (label != null && sawLoop) Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "The label '@$label' does not denote a loop", KotlinDiagnosticCodes.UNRESOLVED_LABEL,
        ) else if (label != null) Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Unresolved label: $label", KotlinDiagnosticCodes.UNRESOLVED_LABEL,
        ) else Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'$word' is only allowed inside a loop", KotlinDiagnosticCodes.JUMP_OUTSIDE_LOOP,
        )
    }

    /**
     * `return@x` where nothing enclosing answers to `x`: not a labeled expression, not the function a lambda
     * is passed to, not an enclosing named function. A lambda that is an argument of something other than a
     * plain named call (`a.b()() { }`, an indexed call) has an implicit label this cannot name, so the whole
     * check backs off the moment one is crossed.
     */
    fun unresolvedReturnLabel(ret: KtReturnExpression): Diagnostic? {
        val label = ret.getLabelName() ?: return null
        var node = ret.parent
        while (node != null) {
            when (node) {
                is KtLabeledExpression -> if (node.getLabelName() == label) return null
                is KtNamedFunction -> if (node.name == label) return null
                is KtFunctionLiteral -> when (val name = implicitLambdaLabel(node)) {
                    null -> return null // an implicit label that cannot be read: do not guess
                    else -> if (name == label) return null
                }
                is KtClassOrObject, is KtFile -> break
                else -> {}
            }
            node = node.parent
        }
        val r = (ret.getTargetLabel() ?: ret).textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Unresolved label: $label", KotlinDiagnosticCodes.UNRESOLVED_LABEL,
        )
    }

    /** The label a lambda gets from the call it is passed to; "" when it is not passed to a call at all (it
     *  then has only an explicit label), null when it is passed to something this cannot name. */
    private fun implicitLambdaLabel(literal: KtFunctionLiteral): String? {
        var holder: KtElement = literal.parent as? KtLambdaExpression ?: return null
        while (true) {
            val p = holder.parent ?: return ""
            if (p is KtLabeledExpression || p is KtParenthesizedExpression) holder = p else break
        }
        return when (val p = holder.parent) {
            is KtLambdaArgument, is KtValueArgument -> {
                val call = (if (p is KtLambdaArgument) p.parent else (p.parent as? KtValueArgumentList)?.parent) as? KtCallExpression
                    ?: return null
                (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            }
            is KtBinaryExpression ->
                if (p.operationToken == KtTokens.IDENTIFIER) p.operationReference.getReferencedName() else ""
            else -> ""
        }
    }

    /**
     * An unlabeled `this` with no receiver to mean: outside every class and object, in no extension function
     * or extension property, and in no lambda (which may have a receiver the tree does not show).
     */
    fun thisWithoutReceiver(expr: KtThisExpression): Diagnostic? {
        if (expr.getLabelName() != null) return null
        val file = expr.containingFile
        if (file.name.endsWith(".kts")) return null // a script's top level has a receiver
        var node = expr.parent
        while (node != null) {
            when (node) {
                is KtClassOrObject, is KtFunctionLiteral -> return null
                is KtNamedFunction -> if (node.receiverTypeReference != null || hasContext(node)) return null
                is KtProperty -> if (node.receiverTypeReference != null || hasContext(node)) return null
                else -> {}
            }
            node = node.parent
        }
        val r = expr.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'this' is not defined in this context", KotlinDiagnosticCodes.NO_THIS,
        )
    }

    private fun hasContext(decl: KtElement): Boolean = decl.text.trimStart().startsWith("context")

    /**
     * An `if` with no `else` in a position that consumes its value: an initializer, an assigned value, a call
     * argument. Without the `else` there is no value on the other path. A statement, a lambda's last
     * statement and an expression body are left alone, since each may be read as a statement.
     */
    fun ifWithoutElseAsValue(expr: KtIfExpression): Diagnostic? {
        if (expr.`else` != null || expr.then == null) return null
        var holder: KtElement = expr
        while (true) {
            val p = holder.parent ?: return null
            if (p is KtParenthesizedExpression || p is KtLabeledExpression) holder = p else break
        }
        val used = when (val p = holder.parent) {
            is KtProperty -> p.initializer === holder
            is KtValueArgument -> p !is KtLambdaArgument
            is KtBinaryExpression -> p.operationToken == KtTokens.EQ && p.right === holder
            else -> false
        }
        if (!used) return null
        val r = expr.firstChild?.textRange ?: expr.textRange // the `if` keyword
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'if' must have both main and 'else' branches when used as an expression",
            KotlinDiagnosticCodes.IF_AS_EXPRESSION,
        )
    }

    /** A `data class` needs at least one primary constructor parameter, and each must be a `val` or `var`. */
    fun dataClassShape(cls: KtClass): List<Diagnostic> {
        if (!cls.isData() || cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return emptyList()
        val params = cls.primaryConstructor?.valueParameters.orEmpty()
        if (params.isEmpty()) {
            val r = (cls.nameIdentifier ?: cls).textRange
            return listOf(Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Data class must have at least one primary constructor parameter", KotlinDiagnosticCodes.DATA_CLASS,
            ))
        }
        return params.filter { !it.hasValOrVar() && it.nameIdentifier != null }.map { p ->
            val r = p.textRange
            Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Primary constructor of data class must only have property ('val' / 'var') parameters",
                KotlinDiagnosticCodes.DATA_CLASS,
            )
        }
    }

    private fun contains(outer: KtElement, inner: KtElement): Boolean {
        val o = outer.textRange
        val i = inner.textRange
        return i.startOffset >= o.startOffset && i.endOffset <= o.endOffset
    }
}
