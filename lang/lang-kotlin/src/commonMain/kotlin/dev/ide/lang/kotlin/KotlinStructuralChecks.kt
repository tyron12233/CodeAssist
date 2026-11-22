package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtTryExpression
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import dev.ide.kotlin.syntax.psi.KtFinallySection
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
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
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
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
                    // A do-while's condition is the exception (K2 prepares that loop's target before building the
                    // condition), so `do { } while (continue)` jumps within that loop.
                    val body = node.body
                    val cond = (node as? KtDoWhileExpression)?.condition
                    if (body != null && contains(body, jump) || cond != null && contains(cond, jump)) {
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
        // Compared without backticks: ``return@`yield` `` names `fun `yield`()` and a `` `yield`@ `` label.
        fun bare(n: String?) = n?.removeSurrounding("`")
        val label = bare(ret.getLabelName()) ?: return null
        var node = ret.parent
        while (node != null) {
            when (node) {
                is KtLabeledExpression -> if (bare(node.getLabelName()) == label) return null
                is KtNamedFunction -> if (bare(node.name) == label) return null
                    // An anonymous function passed to a call is labeled by that call, as a lambda is
                    // (`notInline(fun(b: Boolean) { return@notInline })`).
                    // An explicit label (`l@ fun(i: Int) { }`) replaces that one.
                    else if (node.nameIdentifier == null && node.parent !is KtLabeledExpression) when (val name = implicitCallLabel(node)) {
                        null -> return null
                        else -> if (bare(name) == label) return null
                    }
                is KtFunctionLiteral -> when (val name = implicitLambdaLabel(node)) {
                    null -> return null // an implicit label that cannot be read: do not guess
                    else -> if (bare(name) == label) return null
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

    /**
     * A `return` that may not leave the place it stands in (RETURN_NOT_ALLOWED): on its way out to the function
     * it returns from it crosses a class (a member of a local class or an object literal, an initializer), a
     * member or top-level property's initializer, a parameter's default value, a constructor's delegation
     * call, another function, or a lambda that is not inlined into its caller. [notInlined] answers that last
     * question; it is asked only for a lambda the return passes through, and answering false is always safe.
     */
    fun returnNotAllowed(ret: KtReturnExpression, notInlined: (KtLambdaExpression) -> Boolean): Diagnostic? {
        if (ret.containingFile.name.endsWith(".kts")) return null
        val target = returnTarget(ret) ?: return null
        var node = ret.parent
        while (node != null && node !== target) {
            when (node) {
                is KtFunctionLiteral -> {
                    val lambda = node.parent as? KtLambdaExpression ?: return null
                    if (notInlined(lambda)) return returnNotAllowedAt(ret)
                }
                is KtNamedFunction, is KtPropertyAccessor, is KtSecondaryConstructor, is KtClassOrObject,
                is KtParameter, is KtConstructorDelegationCall -> return returnNotAllowedAt(ret)
                is KtProperty -> if (node.parent is KtClassBody || node.parent is KtFile) return returnNotAllowedAt(ret)
                is KtFile -> return null
                else -> {}
            }
            node = node.parent
        }
        return null
    }

    /** The declaration a `return` leaves: its label's function or lambda, or for a bare `return` the nearest
     *  enclosing function, accessor or constructor (the file when there is none). Null when the label names
     *  nothing this can read. */
    internal fun returnTarget(ret: KtReturnExpression): KtElement? {
        val label = ret.getLabelName()
        var node = ret.parent
        while (node != null) {
            if (label == null) {
                if (node is KtNamedFunction || node is KtPropertyAccessor || node is KtSecondaryConstructor || node is KtFile) return node
            } else when (node) {
                is KtFunctionLiteral -> when (val name = implicitLambdaLabel(node)) {
                    null -> return null
                    label -> return node
                    else -> {}
                }
                is KtLabeledExpression -> if (node.getLabelName() == label) {
                    // A labeled loop is not something to return from; that is a different error.
                    return ((node.baseExpression as? KtLambdaExpression)?.functionLiteral)
                }
                is KtNamedFunction -> if (node.name == label) return node
                is KtFile -> return null
                else -> {}
            }
            node = node.parent
        }
        return null
    }

    private fun returnNotAllowedAt(ret: KtReturnExpression): Diagnostic {
        val start = ret.textRange.startOffset
        val end = ret.getTargetLabel()?.textRange?.endOffset ?: (start + "return".length)
        return Diagnostic(TextRange(start, end), Severity.ERROR, "'return' is prohibited here", KotlinDiagnosticCodes.RETURN_NOT_ALLOWED)
    }

    /** The label a lambda gets from the call it is passed to; "" when it is not passed to a call at all (it
     *  then has only an explicit label), null when it is passed to something this cannot name. */
    private fun implicitLambdaLabel(literal: KtFunctionLiteral): String? =
        implicitCallLabel(literal.parent as? KtLambdaExpression ?: return null)

    /** The label a function value [value] (a lambda expression or an anonymous function) gets from the call it is
     *  passed to; "" when it is passed to none, null when the call is not one this can name. */
    private fun implicitCallLabel(value: KtElement): String? {
        var holder: KtElement = value
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
     * INVALID_IF_AS_EXPRESSION (FirExhaustiveWhenChecker): an `if` whose value is used ([usedAsExpression]) needs an
     * `else` and a non-empty `then` (`val x = if (c) 1`, `return if (c);`, `if (c) else 1`). An `else` keyword with
     * no branch after it is a syntax error of its own, and K2 builds that `if` with its (error) `else`.
     */
    fun ifWithoutElseAsValue(expr: KtIfExpression): Diagnostic? {
        // K2 builds the `else` branch from whatever follows the keyword, a declaration the parser put there
        // included (`else <error> fun named() = 1`); `else;` has none and the `if` is not exhaustive.
        val elseWritten = expr.`else` != null ||
            expr.childrenWithTrivia.firstOrNull { it.elementType == KtNodeTypes.ELSE }?.children?.isNotEmpty() == true
        if (elseWritten && expr.then != null) return null
        if (!usedAsExpression(expr)) return null
        val r = expr.firstChild?.textRange ?: expr.textRange // the `if` keyword
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'if' must have both main and 'else' branches when used as an expression",
            KotlinDiagnosticCodes.IF_AS_EXPRESSION,
        )
    }

    /**
     * Whether the value of [e] is used, the way K2's raw FIR builder decides `usedAsExpression` (PsiRawFirBuilder):
     * a branch of an `if`/`when` is used when that `if`/`when` is; a block's statement only when it is the block's
     * last and the block is used; a lambda's, an initializer's, a secondary constructor's, a `finally`'s and a
     * block-bodied function's statements never; a loop body never; a `.` call's receiver yes, its selector no;
     * every other position (an initializer, an argument, an operand, a `return`, an expression body) yes.
     */
    fun usedAsExpression(e: KtExpression, depth: Int = 0): Boolean {
        if (depth > 64) return true
        var cur: KtElement = e
        var parent = e.parent ?: return false
        while (parent is KtAnnotatedExpression || parent is KtLabeledExpression) {
            cur = parent
            parent = parent.parent ?: return false
        }
        if (parent.elementType == KtNodeTypes.THEN || parent.elementType == KtNodeTypes.ELSE) {
            return (parent.parent as? KtExpression)?.let { usedAsExpression(it, depth + 1) } != false
        }
        if (parent is KtWhenEntry) return (parent.parent as? KtExpression)?.let { usedAsExpression(it, depth + 1) } != false
        return when (parent) {
            is KtBlockExpression -> parent.statements.lastOrNull() === cur && usedAsExpression(parent, depth + 1)
            is KtTryExpression -> parent.tryBlock === cur && usedAsExpression(parent, depth + 1)
            is KtCatchClause -> (parent.parent as? KtTryExpression)?.let { usedAsExpression(it, depth + 1) } == true
            is KtClassInitializer, is KtSecondaryConstructor, is KtFunctionLiteral, is KtFinallySection -> false
            is KtDotQualifiedExpression -> parent.receiverExpression === cur
            is KtNamedFunction -> parent.bodyExpression === cur && !parent.hasBlockBody()
            is KtPropertyAccessor -> parent.bodyExpression === cur && !parent.hasBlockBody()
            is KtContainerNodeForControlStructureBody -> parent.parent !is KtLoopExpression
            else -> true
        }
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
