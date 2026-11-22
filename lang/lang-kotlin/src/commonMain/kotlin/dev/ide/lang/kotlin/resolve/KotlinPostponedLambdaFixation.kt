package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtWhenConditionInRange
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * Fixation on demand inside a builder lambda (the compiler's PCLA, postponed call lambda analysis).
 *
 * A lambda whose parameter or receiver type mentions a type variable of the call it is passed to, with nothing
 * else in that call constraining it (`fun <OT> pcla(lambda: (TypeVariableOwner<OT>) -> Unit): OT`, `buildList`),
 * is analyzed while the variable is still open: its statements only collect constraints on it
 * (`otvOwner.constrain(ScopeOwner())` makes `OT >: ScopeOwner`). The moment the body needs the variable's
 * MEMBERS (a value of that type used as a receiver, an operand of an operator, a range, a `for` container), the
 * compiler fixes it to the common supertype of what it has so far, and from then on an argument for it must fit
 * that type: `otvOwner.constrain(Interloper)` after the fixing statement is an argument type mismatch.
 *
 * Read conservatively: only the lambda's own top-level statements are ordered, every constraint before the
 * fixing statement must be an argument for a parameter typed by the bare variable whose type is known, and the
 * fixing statement must add no constraint of its own. Anything else leaves the variable unfixed, so no argument
 * is judged against it.
 */
internal class PclaFixation(val statementIndex: Int, val fixed: KotlinType)

/**
 * The type the builder variable [variable] has been fixed to by the point [at], or null while it is still open
 * there (or where this reading does not apply). [at] is a call argument's call whose parameter is typed by the
 * bare variable.
 */
internal fun KotlinResolver.postponedVariableFixedAt(at: KtElement, variable: String): KotlinType? {
    var lambda: KtLambdaExpression? = null
    var up: KtElement? = at.parent
    while (up != null && !(up is KtNamedFunction && up.nameIdentifier != null) && up !is KtClassOrObject) {
        if (up is KtLambdaExpression && ownsVariable(up, variable)) {
            // Two enclosing builders over the same variable name cannot be told apart by name.
            if (lambda != null) return null
            lambda = up
        }
        up = up.parent
    }
    val l = lambda ?: return null
    val statements = l.bodyExpression?.statements ?: return null
    val r = at.textRange
    val index = statements.indexOfFirst { it.textRange.startOffset <= r.startOffset && r.endOffset <= it.textRange.endOffset }
    if (index < 0) return null
    val fixation = caches.pclaFixation.getOrPut(l to variable) { computeFixation(l, variable) } ?: return null
    return if (index > fixation.statementIndex) fixation.fixed else null
}

/** Whether [lambda] is the builder lambda of a call whose own type variable [variable] reaches the lambda only
 *  through its inputs: no explicit type argument, no other argument and no expected type constrain it. */
private fun KotlinResolver.ownsVariable(lambda: KtLambdaExpression, variable: String): Boolean {
    val (call, argIndex) = enclosingCallAndParamIndex(lambda) ?: return false
    val sym = resolveCalleeFunction(call) ?: return false
    if (variable !in sym.typeParameters || call.typeArgumentList != null) return false
    val names = sym.typeParameters.toSet()
    val slot = lambdaParamIndex(call, argIndex, sym)
    val p = service.markTypeParameters(sym.paramTypes.getOrNull(slot) as? KotlinType, names) ?: return false
    val shape = service.functionalShape(p) ?: return false
    val inputs = shape.parameterTypes + listOfNotNull(shape.receiverType)
    if (inputs.none { mentionsTypeParam(it, variable) }) return false
    call.valueArguments.forEachIndexed { i, _ ->
        if (i == argIndex) return@forEachIndexed
        val other = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType ?: return false
        if (mentionsTypeParam(service.markTypeParameters(other, names), variable)) return false
    }
    if (variable == sym.receiverTypeParam || sym.receiverTypeArgs.any { mentionsTypeParam(it, variable) }) return false
    val ret = service.markTypeParameters(sym.type as? KotlinType, names)
    if (ret != null && mentionsTypeParam(ret, variable) && expectedTypeOf(call) != null) return false
    return true
}

private fun KotlinResolver.computeFixation(lambda: KtLambdaExpression, variable: String): PclaFixation? {
    val statements = lambda.bodyExpression?.statements ?: return null
    val lowers = ArrayList<KotlinType>()
    for ((i, s) in statements.withIndex()) {
        val scan = StatementScan()
        scanStatement(s, variable, scan)
        if (scan.trigger) {
            if (scan.unknown || scan.lowers.isNotEmpty() || lowers.isEmpty()) return null
            val cs = KotlinConstraintSystem(service)
            cs.registerVariable(variable)
            val v = KotlinType(variable, isTypeParameter = true)
            lowers.forEach { cs.addSubtypeConstraint(it, v) }
            val fixed = cs.solve()[variable] as? KotlinType ?: return null
            if (fixed.isTypeParameter || !service.isKnownType(fixed.qualifiedName)) return null
            return PclaFixation(i, fixed)
        }
        if (scan.unknown) return null
        lowers += scan.lowers
    }
    return null
}

private class StatementScan {
    val lowers = ArrayList<KotlinType>()
    var unknown = false
    var trigger = false
}

/** Collect, from one statement, the arguments it passes for a parameter typed by [variable] (lower bounds) and
 *  whether it needs the variable's members (a fixation point). A parameter that mentions the variable inside
 *  some other type, or an argument of unknown type, makes the statement [StatementScan.unknown]. */
private fun KotlinResolver.scanStatement(root: KtElement, variable: String, scan: StatementScan) {
    fun visit(e: KtElement) {
        if (scan.unknown) return
        if (e is KtCallExpression) {
            val sym = resolveCalleeFunction(e)
            // A nested builder over a variable of the same name is its own variable.
            if (sym != null && variable !in sym.typeParameters) collectArgumentBounds(e, sym, variable, scan)
        }
        if (!scan.trigger && needsMembers(e, variable)) scan.trigger = true
        var c = e.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    visit(root)
}

private fun KotlinResolver.collectArgumentBounds(call: KtCallExpression, sym: KotlinSymbol, variable: String, scan: StatementScan) {
    call.valueArguments.forEachIndexed { i, arg ->
        val p = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType ?: return@forEachIndexed
        if (isBareVariable(p, variable)) {
            val t = arg.getArgumentExpression()?.let { inferType(it) }
            if (t == null || t.isTypeParameter || arg.getSpreadElement() != null) scan.unknown = true
            else scan.lowers += t.withNullable(t.nullable && !p.nullable)
        } else if (mentionsName(p, variable)) {
            scan.unknown = true
        }
    }
}

/** Whether [e] is a use of a value typed by the bare [variable] that needs that type's members. Equality,
 *  identity, a string template and an `is`/`as` against a fully written type do not; `++`/`--` are left alone
 *  too. An `is`/`as` against a generic class written bare (`is GenericSubtype`) does: bare type inference takes the
 *  missing type arguments from the value's type, which must be fixed for that. */
private fun KotlinResolver.needsMembers(e: KtElement, variable: String): Boolean {
    fun typed(x: KtExpression?): Boolean =
        x != null && inferType(x)?.let { isBareVariable(it, variable) } == true
    return when (e) {
        is KtWhenConditionInRange -> typed(e.rangeExpression)
        is KtIsExpression -> bareGenericType(e.typeReference) && typed(e.leftHandSide)
        is KtBinaryExpressionWithTypeRHS -> bareGenericType(e.right) && typed(e.left)
        is KtWhenConditionIsPattern -> bareGenericType(e.typeReference) &&
            (e.parent?.parent as? KtWhenExpression)?.let { w -> w.subjectVariable == null && typed(w.subjectExpression) } == true
        is KtQualifiedExpression -> e.selectorExpression != null && typed(e.receiverExpression)
        is KtCallableReferenceExpression -> typed(e.receiverExpression)
        is KtArrayAccessExpression -> typed(e.arrayExpression)
        is KtForExpression -> typed(e.loopRange)
        is KtPrefixExpression -> e.operationToken in UNARY_OPERATORS && typed(e.baseExpression)
        is KtBinaryExpression -> when (e.operationToken) {
            in BINARY_OPERATORS -> typed(e.left)
            KtTokens.IN_KEYWORD, KtTokens.NOT_IN -> typed(e.right)
            else -> false
        }
        else -> false
    }
}

/** A written type naming a generic class without its type arguments (`GenericScopeOwnerSubtype`), which bare type
 *  inference completes from the checked value's type. */
private fun KotlinResolver.bareGenericType(ref: KtTypeReference?): Boolean {
    val user = ref?.typeElement as? KtUserType ?: return false
    if (user.typeArgumentList != null) return false
    val fqn = service.resolveTypeName(user.text, fileContext) ?: return false
    return service.classTypeParameters(fqn).isNotEmpty()
}

private val UNARY_OPERATORS = setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.EXCL)

private val BINARY_OPERATORS = setOf(
    KtTokens.IDENTIFIER, KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC,
    KtTokens.RANGE, KtTokens.RANGE_UNTIL, KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
    KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
)

/** [t] is the type variable [variable] itself (nullable or not): marked as one, or an unknown classifier
 *  spelled exactly like it (a symbol read back from the index may not mark it). */
private fun KotlinResolver.isBareVariable(t: KotlinType, variable: String): Boolean =
    t.typeArguments.isEmpty() && t.qualifiedName == variable &&
        (t.isTypeParameter || !service.isKnownType(t.qualifiedName))

private fun mentionsName(t: KotlinType, variable: String): Boolean =
    (t.typeArguments.isEmpty() && t.qualifiedName == variable) ||
        t.typeArguments.any { it is KotlinType && mentionsName(it, variable) }
