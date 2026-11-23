package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtPropertyDelegate
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpressionBase
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.kotlin.syntax.psi.parents
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.implicitReceiverMember
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.isTypeReceiver
import dev.ide.lang.kotlin.resolve.iterationElementType
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.pathGuardedNonNullAt
import dev.ide.lang.kotlin.resolve.receiverForMembers
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.resolve.smartCastNonNull
import dev.ide.lang.kotlin.resolve.flowProvenNull
import dev.ide.lang.kotlin.resolve.flowProvenTypes
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/*
 * The typed half of the error-recall work: checks for plain compile errors the editor used to pass over
 * (a nullable value where a non-null one is required, `Int = 1L`, a branch of the wrong type, `if (1)`,
 * `throw "x"`, a private member reached from outside, an assignment to a `val` member, ...).
 *
 * They hold to the contract the rest of [KotlinSemanticChecks] keeps: report only what the symbol model can
 * decide, and say nothing when a type is unknown, a type parameter, or read from Java (a platform type).
 * Every one of them names the narrow population it trusts, because the model's types are best-effort and a
 * wrong one must cost a missed error rather than an invented one.
 */

private const val BOOLEAN = "kotlin.Boolean"
private const val UNIT = "kotlin.Unit"
private const val NOTHING = "kotlin.Nothing"
private const val THROWABLE = "kotlin.Throwable"

private val INTEGER_TYPES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte")

/** The built-in value types whose whole API the model knows: nothing but what it lists is a member of one. */
private val CLOSED_VALUE_TYPES = setOf("kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Unit")

private val ARITHMETIC_NAMES = mapOf(
    KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times",
    KtTokens.DIV to "div", KtTokens.PERC to "rem",
)
private val ORDER_TOKENS = setOf(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ)

/** The other binary conventions [undefinedBinaryOperator] judges. */
private val CONVENTION_NAMES = mapOf(
    KtTokens.RANGE to "rangeTo", KtTokens.RANGE_UNTIL to "rangeUntil",
    KtTokens.LT to "compareTo", KtTokens.GT to "compareTo", KtTokens.LTEQ to "compareTo", KtTokens.GTEQ to "compareTo",
    KtTokens.IN_KEYWORD to "contains", KtTokens.NOT_IN to "contains",
)

/** A compound assignment's `xAssign` and the plain operator it falls back to. */
private val COMPOUND_NAMES = mapOf(
    KtTokens.PLUSEQ to ("plusAssign" to "plus"), KtTokens.MINUSEQ to ("minusAssign" to "minus"),
    KtTokens.MULTEQ to ("timesAssign" to "times"), KtTokens.DIVEQ to ("divAssign" to "div"),
    KtTokens.PERCEQ to ("remAssign" to "rem"),
)

private val NUMERIC_CONVERSIONS = setOf("toInt", "toLong", "toShort", "toByte", "toDouble", "toFloat")

/** Types with indexed READ access and no `set`: assigning through `[]` can never work on one. */
private val READ_ONLY_INDEXED = setOf(
    "kotlin.collections.List", "kotlin.collections.Collection", "kotlin.collections.Iterable",
    "kotlin.collections.Set", "kotlin.collections.Map", "kotlin.String", "kotlin.CharSequence",
)

/** Read-only properties of built-in types, by owner. Source properties are judged from their declaration. */
private val BUILTIN_READ_ONLY = mapOf(
    "kotlin.String" to setOf("length", "indices", "lastIndex"),
    "kotlin.CharSequence" to setOf("length", "indices", "lastIndex"),
    "kotlin.collections.List" to setOf("size", "indices", "lastIndex"),
    "kotlin.collections.MutableList" to setOf("size", "indices", "lastIndex"),
    "kotlin.collections.Collection" to setOf("size", "indices"),
    "kotlin.collections.Set" to setOf("size"),
    "kotlin.collections.MutableSet" to setOf("size"),
    "kotlin.collections.Map" to setOf("size", "keys", "values", "entries"),
    "kotlin.collections.MutableMap" to setOf("size", "keys", "values", "entries"),
    "kotlin.Array" to setOf("size", "indices", "lastIndex"),
    "kotlin.Pair" to setOf("first", "second"),
    "kotlin.Triple" to setOf("first", "second", "third"),
)

private fun KotlinSymbol.sourcePsi(): KtElement? =
    if (origin.fromSource) (declaration() as? KotlinDomNode)?.psi else null

private fun canonicalName(t: KotlinType): String = canonicalTypeForCheck(t).qualifiedName

private fun KotlinSemanticChecks.error(at: KtElement, message: String, code: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

// --- a value against the type it must have -----------------------------------------------------------------

/**
 * [value] against the [declared] type it is assigned, returned or initialized to.
 *
 * The whole expression first, as before. Then, where it is a choice between values (`if`/`when`/`try`, the
 * right side of `?:`), each alternative on its own: the type of a choice is only known when its branches
 * agree, so `if (b) 1 else "a"` had no type at all and passed for anything.
 *
 * [platformTarget] is a target declared in Java, whose type admits `null` whatever it says.
 */
internal fun KotlinSemanticChecks.valueMismatch(
    declared: KotlinType?, value: KtExpression, resolver: KotlinResolver, platformTarget: Boolean = false,
    strictNumeric: Boolean = true, depth: Int = 0,
): Diagnostic? {
    if (declared == null) return null
    val e = unwrapParen(value)
    if (!platformTarget) nullForNonNull(declared, e)?.let { return it }
    if (flowFits(declared, e, resolver)) return null
    // A choice one of whose branches is a smart-cast name inference cannot type in full is judged branch by branch.
    if (depth == 0 && choiceBranches(e).any { b -> branchValue(b)?.let { unwrapParen(it) }?.let { flowFits(declared, it, resolver) } == true }) {
        for (alt in choiceBranches(e)) {
            val leaf = branchValue(alt) ?: continue
            valueMismatch(declared, leaf, resolver, platformTarget, strictNumeric, depth + 1)?.let { return it }
        }
        return null
    }
    if (isFunctionLiteral(e) && neverAFunction(declared)) {
        // Inference types a lambda from what is expected of it, so with no function type expected it may have none.
        resolver.inferType(e)?.let { return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, it, declared) }
        return error(e, "Type mismatch: a function value is not a ${renderType(declared)}", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }
    val actual = resolver.inferType(e)
    // A branch of a choice is judged on its own only when its type is one to rely on: a literal, or a local
    // whose type was just read off its declaration. The whole expression (depth 0) keeps the rule it had.
    val trusted = depth == 0 || plainlyTyped(e)
    if (trusted && actual != null && isMismatch(declared, actual)) {
        return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, actual, declared)
    }
    if (trusted && !platformTarget) typeArgumentMismatch(declared, e, resolver)?.let {
        return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, it, declared)
    }
    if (trusted && strictNumeric && numericMismatch(declared, actual, e, resolver)) {
        val shown = actual ?: numericConst(e, resolver)?.let { service.typeByFqn(it.default) } ?: return null
        return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, shown, declared)
    }
    if (depth < 6) {
        val alternatives: List<KtExpression?> = when (e) {
            is KtIfExpression -> if (e.`else` != null) listOf(e.then, e.`else`) else emptyList()
            is KtWhenExpression -> e.entries.map { it.expression }
            is KtTryExpression -> listOf<KtExpression?>(e.tryBlock) + e.catchClauses.map { it.catchBody }
            is KtBinaryExpression -> if (e.operationToken == KtTokens.ELVIS) listOf(e.right) else emptyList()
            else -> emptyList()
        }
        for (alt in alternatives) {
            // An empty block branch is `Unit`, which only `Unit`, `Any` and nullable types take
            // (`fun f(): Int = if (c) {} else 2`).
            if (e is KtIfExpression && alt is KtBlockExpression && alt.statements.isEmpty() && !declared.nullable &&
                !declared.isTypeParameter && canonicalName(declared).let { it != UNIT && it != "kotlin.Any" && it != NOTHING && service.isKnownType(it) }
            ) return error(e, "Type mismatch: inferred type is Unit but ${renderType(declared)} was expected", KotlinDiagnosticCodes.TYPE_MISMATCH)
            val leaf = branchValue(alt) ?: continue
            valueMismatch(declared, leaf, resolver, platformTarget, strictNumeric, depth + 1)?.let { return it }
        }
        if (alternatives.isNotEmpty()) return null // nullability of a choice is the nullability of its branches
    }
    if (!platformTarget && nullableForNonNull(declared, actual, e, resolver)) {
        return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, actual!!.withNullable(true), declared)
    }
    return null
}

/** A lambda or an anonymous function: a function value by its very shape, whatever inference makes of it. */
internal fun isFunctionLiteral(e: KtExpression): Boolean = when (val u = unwrapParen(e)) {
    is KtLambdaExpression -> true
    is KtNamedFunction -> u.nameIdentifier == null
    is KtLabeledExpression -> u.baseExpression?.let(::isFunctionLiteral) == true
    else -> false
}

/**
 * Whether no function value can ever be a [declared]: a known class, not an interface (a lambda is a
 * `Serializable`, and a fun interface takes one through SAM conversion), not `Any`, and no function type.
 */
internal fun KotlinSemanticChecks.neverAFunction(declared: KotlinType): Boolean {
    if (declared.isTypeParameter) return false
    val d = canonicalTypeForCheck(declared)
    // A lambda's value may be coerced to Unit where Unit is expected of it, so leave Unit out too.
    if (d.qualifiedName == "kotlin.Any" || d.qualifiedName == UNIT || isFunctional(d) || TypeRendering.isFunctionType(d.qualifiedName)) return false
    if (d.qualifiedName.startsWith("kotlin.jvm.internal.") || d.qualifiedName == "kotlin.Function") return false
    if (service.isInterfaceType(d.qualifiedName) != false) return false
    return isConcreteNonFunction(d)
}

/**
 * Whether the flow shows the value [e] fits [declared] where its inferred type does not: a value proven null
 * (`if (i == null) foo(i)`) is a `Nothing?`, which fits any nullable type, and a value smart-cast to several types
 * at once (`b is A && b is C`) has all of them while inference names one.
 */
internal fun flowFits(declared: KotlinType, e: KtExpression, resolver: KotlinResolver): Boolean {
    if (e !is KtNameReferenceExpression || declared.isTypeParameter) return false
    if (declared.nullable && resolver.flowProvenNull(e)) return true
    return resolver.flowProvenTypes(e, deep = true).any { declared.withNullable(false).isAssignableFrom(it) }
}

/** The alternatives of an `if`/`when`/`try`/`?:` whose value is one of them. */
private fun choiceBranches(e: KtExpression): List<KtExpression?> = when (e) {
    is KtIfExpression -> if (e.`else` != null) listOf(e.then, e.`else`) else emptyList()
    is KtWhenExpression -> e.entries.map { it.expression }
    is KtTryExpression -> listOf<KtExpression?>(e.tryBlock) + e.catchClauses.map { it.catchBody }
    is KtBinaryExpression -> if (e.operationToken == KtTokens.ELVIS) listOf(e.right) else emptyList()
    else -> emptyList()
}

/** The value a branch produces: itself, or the last statement of its block when that is an expression. */
private fun branchValue(branch: KtExpression?): KtExpression? = when (branch) {
    null -> null
    is KtBlockExpression -> branch.statements.lastOrNull()?.takeIf { it !is KtDeclaration }
    else -> branch
}

/**
 * A nullable value where a non-null one is required, both of the SAME known type otherwise (a different type
 * is [KotlinSemanticChecks.isMismatch]'s business).
 *
 * The nullability of an inferred type is only as good as the flow analysis behind it, so this trusts it for
 * the shapes that analysis models: a plain name (unless a guard smart-casts it), a member or call through a
 * path with no guard on it, an indexed read, a safe call on a receiver that is itself nullable, an `as?`.
 */
internal fun KotlinSemanticChecks.nullableForNonNull(
    declared: KotlinType, actual: KotlinType?, value: KtExpression, resolver: KotlinResolver,
): Boolean {
    if (actual == null || declared.nullable) return false
    // A safe call and a `map[key]` read are nullable by construction, whatever the member's own type says.
    if (!actual.nullable && !nullableByShape(value, resolver)) return false
    if (declared.isTypeParameter || actual.isTypeParameter) return false
    val d = canonicalTypeForCheck(declared)
    val a = canonicalTypeForCheck(actual)
    if (d.qualifiedName == NOTHING || a.qualifiedName == NOTHING) return false
    if (isFunctional(d) || isFunctional(a)) return false
    if (!service.isKnownType(d.qualifiedName) || !service.isKnownType(a.qualifiedName)) return false
    return reliablyNullable(value, resolver)
}

/** `a?.b` on a nullable `a`, and `map[key]`: nullable results the member's declared type does not carry. */
internal fun KotlinSemanticChecks.nullableByShape(value: KtExpression, resolver: KotlinResolver): Boolean =
    when (val e = unwrapParen(value)) {
        is KtSafeQualifiedExpression -> resolver.inferType(e.receiverExpression)?.nullable == true
        is KtArrayAccessExpression -> isMapRead(e, resolver)
        else -> false
    }

/** `map[key]` where `map` is a `Map` or `MutableMap`: its `get` returns `V?`. */
internal fun KotlinSemanticChecks.isMapRead(e: KtArrayAccessExpression, resolver: KotlinResolver): Boolean {
    val bt = e.arrayExpression?.let { resolver.inferType(it) } ?: return false
    if (bt.nullable || bt.isTypeParameter || e.indexExpressions.size != 1) return false
    return canonicalName(bt).let { it == "kotlin.collections.Map" || it == "kotlin.collections.MutableMap" }
}

/** Whether every function [call] can resolve to is a Kotlin declaration whose written return type is nullable. A
 *  Java method's nullability comes from annotations the model reads differently, and a result nullable only
 *  through an inferred type argument is inference's opinion; neither is taken. */
private fun KotlinSemanticChecks.declaresNullableResult(call: KtCallExpression, resolver: KotlinResolver): Boolean {
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
    if (targets.isEmpty()) return false
    return targets.all { sym ->
        sym.kind == SymbolKind.METHOD && (sym.type as? KotlinType)?.nullable == true &&
            sym.declaringClassFqn?.let { service.isJavaType(it) } != true
    }
}

/** Whether [value]'s nullable type can be trusted: no guard this model cannot follow stands in front of it. */
internal fun KotlinSemanticChecks.reliablyNullable(value: KtExpression, resolver: KotlinResolver): Boolean {
    return when (val e = unwrapParen(value)) {
        is KtNameReferenceExpression -> firstUseOfNullableLocal(e, resolver) || flowNullableLocal(e, resolver)
        is KtSafeQualifiedExpression -> {
            val receiver = e.receiverExpression
            resolver.inferType(receiver)?.nullable == true && reliablyNullable(receiver, resolver)
        }
        is KtBinaryExpressionWithTypeRHS -> e.operationReference.text.trim() == "as?"
        // A call's result is never smart cast: a return type its callee writes nullable (`fun f(): T?`, `Map.get`)
        // is the type of the value.
        is KtCallExpression -> declaresNullableResult(e, resolver)
        is KtDotQualifiedExpression -> (e.selectorExpression as? KtCallExpression)?.let { declaresNullableResult(it, resolver) } == true
        is KtArrayAccessExpression -> {
            // `map[key]` on a real Map. Anything else with a nullable `get` may be a user operator.
            isMapRead(e, resolver) &&
                !resolver.pathGuardedNonNullAt(e.text, e.textRange.startOffset) && !hasUnmodeledNullGuard(e, e.text)
        }
        else -> false
    }
}

/**
 * A number of one built-in type where another is required. Kotlin never widens: `val x: Int = 1L`,
 * `val d: Double = 1` and `val y: Int = aLong` are all errors. An INTEGER LITERAL is the one thing that
 * adapts, and only among the integer types.
 *
 * Judged for a literal, a plain name, a property read and an explicit `toX()` conversion. An arithmetic
 * expression is not: a constant one (`1 + 2`) adapts like a literal, and the promotion rules for the rest
 * are a guess this check does not need to make.
 */
internal fun KotlinSemanticChecks.numericMismatch(
    declared: KotlinType?, actual: KotlinType?, value: KtExpression, resolver: KotlinResolver? = null,
): Boolean {
    if (declared == null || declared.isTypeParameter) return false
    val d = canonicalName(declared)
    // A number expression read off the tree (literals in range, arithmetic on them, conversions, plainly
    // typed names) settles it whatever inference made of it.
    if (d in NUMERIC && resolver != null) numericConst(value, resolver)?.let { return d !in it.types }
    if (actual == null) return false
    val a = canonicalName(actual)
    if (d !in NUMERIC || a !in NUMERIC || d == a) return false
    var e = unwrapParen(value)
    if (e is KtPrefixExpression && (e.operationToken == KtTokens.MINUS || e.operationToken == KtTokens.PLUS)) {
        e = e.baseExpression?.let { unwrapParen(it) } ?: return false
        if (e !is KtConstantExpression) return false
    }
    return when (e) {
        is KtConstantExpression -> {
            val text = e.text.trim()
            val hex = text.startsWith("0x", true) || text.startsWith("0b", true)
            val isLong = text.endsWith("L") || text.endsWith("l")
            val floating = !hex && (text.endsWith("f", true) || '.' in text || 'e' in text || 'E' in text)
            // An unsuffixed integer literal takes whichever integer type is asked for.
            if (!floating && !isLong) d !in INTEGER_TYPES else true
        }
        // A local or parameter whose numeric type is WRITTEN on its declaration: an inferred one is a guess.
        is KtNameReferenceExpression -> explicitTypeTextOf(e)?.let { it == a.substringAfterLast('.') } == true
        is KtDotQualifiedExpression -> when (val sel = e.selectorExpression) {
            is KtCallExpression ->
                (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() in NUMERIC_CONVERSIONS &&
                    sel.valueArguments.isEmpty()
            else -> false
        }
        else -> false
    }
}

/** A bare `return` in a function that declares a result. */
internal fun KotlinSemanticChecks.returnWithoutValue(ret: KtReturnExpression, resolver: KotlinResolver): Diagnostic? {
    if (ret.returnedExpression != null || ret.getLabelName() != null) return null
    var node = ret.parent
    var fn: KtNamedFunction? = null
    while (node != null) {
        when (node) {
            is KtFunctionLiteral, is KtPropertyAccessor, is KtSecondaryConstructor, is KtClassOrObject -> return null
            is KtNamedFunction -> { fn = node; break }
            else -> {}
        }
        node = node.parent
    }
    if (fn == null || !fn.hasBlockBody()) return null
    val declaredText = fn.typeReference?.text ?: return null
    val declared = service.typeFromText(declaredText, resolver.fileContext, enclosingClassFqnOf(fn)) ?: return null
    if (declared.isTypeParameter) return null
    val name = canonicalName(declared)
    if (name == UNIT || name == NOTHING || !service.isKnownType(name)) return null
    return error(ret, "This function must return a value of type ${renderType(declared)}", KotlinDiagnosticCodes.RETURN_VALUE_EXPECTED)
}

// --- fixed slots --------------------------------------------------------------------------------------------

/** `if (1)`, `while ("x")`: a condition of a built-in value type that is not Boolean. */
internal fun KotlinSemanticChecks.conditionNotBoolean(condition: KtExpression?, resolver: KotlinResolver): Diagnostic? {
    condition ?: return null
    val t = resolver.inferType(condition) ?: return null
    if (t.isTypeParameter || t.nullable) return null
    val name = canonicalName(t)
    if (name == BOOLEAN || (name !in FINAL_VALUE_TYPES && name != UNIT)) return null
    return error(
        condition, "Condition type mismatch: inferred type is ${renderType(t)} but Boolean was expected",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )
}

/** `for (x in 5)`: a range of a scalar type with no `iterator()`; and a loop variable declared with a type
 *  the elements cannot have (`for (i: Int in strings)`). */
internal fun KotlinSemanticChecks.forLoopMismatch(loop: KtForExpression, resolver: KotlinResolver): Diagnostic? {
    val range = loop.loopRange ?: return null
    val rt = resolver.inferType(range) ?: return null
    if (rt.isTypeParameter) return null
    val name = canonicalName(rt)
    if (!rt.nullable && (name in NUMERIC || name == BOOLEAN || name == UNIT)) {
        if (!hasOperator(rt, "iterator", range, resolver)) {
            return error(range, "For-loop range must have an 'iterator()' method", KotlinDiagnosticCodes.NOT_ITERABLE)
        }
        return null
    }
    val param = loop.loopParameter ?: return null
    val typeRef = param.typeReference ?: return null
    val declared = service.typeFromText(typeRef.text, resolver.fileContext, enclosingClassFqnOf(param)) ?: return null
    val element = resolver.iterationElementType(rt) ?: return null
    if (!isMismatch(declared, element)) return null
    return mismatchDiagnostic(typeRef.textRange.startOffset, typeRef.textRange.endOffset, element, declared)
}

/** Whether [type] has an operator function [name]: a member, an extension in scope, or a member extension. */
private fun KotlinSemanticChecks.hasOperator(type: KotlinType, name: String, at: KtElement, resolver: KotlinResolver): Boolean {
    val ctx = resolver.fileContext
    val c = canonicalTypeForCheck(type)
    if (service.membersNamedForCheck(c.qualifiedName, c.typeArguments, name)
            .any { it.name == name && (!it.isExtension || extensionInScope(it, ctx)) }
    ) return true
    return resolver.scopeMemberExtensions(at.textRange.startOffset, c, name).any { it.name == name }
}

/**
 * `"a" - 1`, `true * 2`, `Foo() + x`, `x in obj`, `a < b`: a binary operator whose convention function the
 * receiver does not have at all. Judged where the receiver's members are known in full: a built-in value type, or
 * a project class or object whose whole supertype chain is project source or built-ins. Any member of that name
 * (an `operator`-less one is OPERATOR_MODIFIER_REQUIRED, a mistyped one an argument error), an applicable
 * extension in scope, or a member extension of an implicit receiver backs off. A compound `x += y` needs neither
 * `plusAssign` nor `plus`.
 */
internal fun KotlinSemanticChecks.undefinedBinaryOperator(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val token = e.operationToken ?: return null
    val compound = COMPOUND_NAMES[token]
    val name = compound?.second ?: ARITHMETIC_NAMES[token] ?: CONVENTION_NAMES[token] ?: return null
    val left = e.left ?: return null
    val right = e.right ?: return null
    // `a in b` calls `b.contains(a)`.
    val receiver = if (name == "contains") right else left
    // An import renamed to the convention name (`import a.plus as minus`) is what `-` calls.
    if (renamedOnImport(resolver, name, compound?.first)) return null
    // `a[i] += v` goes through `get`/`set`: judged elsewhere.
    if (compound != null && unwrapParen(left) !is KtNameReferenceExpression) return null
    val lt = operatorReceiverType(receiver, resolver) ?: return null
    if (lt.isTypeParameter || lt.nullable) return null
    if (!membersKnownInFull(lt)) return null
    if (hasOperator(lt, name, receiver, resolver)) return null
    if (compound != null && hasOperator(lt, compound.first, receiver, resolver)) return null
    return error(
        e.operationReference, "Unresolved reference: $name (no such operator on ${renderType(lt)})",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}

/** `!5`, `-"a"`, `+obj`, `s++`: a unary operator whose convention function the receiver does not have at all
 *  (judged as [undefinedBinaryOperator] judges a binary one). */
internal fun KotlinSemanticChecks.undefinedUnaryOperator(e: KtUnaryExpression, resolver: KotlinResolver): Diagnostic? {
    val name = when (e.operationToken) {
        KtTokens.EXCL -> "not"; KtTokens.MINUS -> "unaryMinus"; KtTokens.PLUS -> "unaryPlus"
        KtTokens.PLUSPLUS -> "inc"; KtTokens.MINUSMINUS -> "dec"
        else -> return null
    }
    val operand = e.baseExpression ?: return null
    if (renamedOnImport(resolver, name, null)) return null
    val t = operatorReceiverType(operand, resolver) ?: return null
    if (t.isTypeParameter || t.nullable) return null
    val tn = canonicalName(t)
    if (name == "not" && tn == BOOLEAN) return null
    if (!(name == "not" && tn in NUMERIC) && !membersKnownInFull(t)) return null
    if (hasOperator(t, name, operand, resolver)) return null
    return error(
        e.operationReference, "Unresolved reference: $name (no such operator on ${renderType(t)})",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}

private fun renamedOnImport(resolver: KotlinResolver, name: String, other: String?): Boolean =
    resolver.fileContext.imports.any { it.alias != null && (it.alias == name || it.alias == other) }

/** The type an operator's receiver [e] has. A class NAME used as a value is its companion object, which the
 *  inferred type does not say, so only an object's own name is judged. */
private fun KotlinSemanticChecks.operatorReceiverType(e: KtExpression, resolver: KotlinResolver): KotlinType? {
    val t = resolver.inferType(e) ?: return null
    if (!resolver.isTypeReceiver(e)) return t
    val decl = (service.sourceClass(t.qualifiedName)?.node as? KotlinDomNode)?.psi
    return t.takeIf { decl is KtObjectDeclaration && !decl.isCompanion() }
}

/** Whether every function [t] could offer as an operator convention is known: a built-in value type, or a
 *  project class/object whose supertype chain is wholly project source or built-ins. A numeric type has all the
 *  arithmetic, comparison and inc/dec operators, so only `not` is judged on one (above). */
private fun KotlinSemanticChecks.membersKnownInFull(t: KotlinType): Boolean {
    val c = canonicalName(t)
    if (c in CLOSED_VALUE_TYPES) return true
    if (c in NUMERIC) return false
    return service.sourceClass(c) != null && service.supertypeClosureKnown(c)
}

/** `a + 1` / `a < b` where `a` is a nullable number: the operator is a member call on a nullable receiver. */
internal fun KotlinSemanticChecks.operatorOnNullable(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken !in ARITHMETIC_NAMES && e.operationToken !in ORDER_TOKENS) return null
    val left = e.left ?: return null
    e.right ?: return null
    val lt = resolver.inferType(left) ?: return null
    if (!lt.nullable || lt.isTypeParameter || canonicalName(lt) !in NUMERIC) return null
    if (!reliablyNullable(left, resolver)) return null
    return error(
        left, "Operator call is not allowed on a nullable receiver of type ${renderType(lt)}",
        KotlinDiagnosticCodes.UNSAFE_NULLABLE,
    )
}

/** Whether a value of [t] can never be a `Throwable`: its supertypes are all known and none of them is one. */
private fun KotlinSemanticChecks.neverThrowable(t: KotlinType): Boolean {
    if (t.isTypeParameter) return false
    val name = canonicalName(t)
    if (name == NOTHING || !service.isKnownType(name)) return false
    if (!(name in FINAL_VALUE_TYPES || name == UNIT || service.supertypeClosureKnown(name))) return false
    fun throwable(n: String) = n == THROWABLE || n == "java.lang.Throwable"
    if (throwable(name)) return false
    if (service.typeByFqn(THROWABLE).isAssignableFrom(t)) return false
    return service.supertypesOf(name).none { throwable(it.qualifiedName) }
}

internal fun KotlinSemanticChecks.throwsNonThrowable(e: KtThrowExpression, resolver: KotlinResolver): Diagnostic? {
    val thrown = e.thrownExpression ?: return null
    val t = resolver.inferType(thrown) ?: return null
    if (!neverThrowable(t)) return null
    return error(
        thrown, "Type mismatch: inferred type is ${renderType(t)} but Throwable was expected",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )
}

internal fun KotlinSemanticChecks.catchesNonThrowable(clause: KtCatchClause, resolver: KotlinResolver): Diagnostic? {
    val typeRef = clause.catchParameter?.typeReference ?: return null
    val t = service.typeFromText(typeRef.text, resolver.fileContext, enclosingClassFqnOf(clause)) ?: return null
    if (!neverThrowable(t)) return null
    return error(
        typeRef, "Type mismatch: a catch parameter must be a Throwable, but ${renderType(t)} is not",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )
}

/** `when (anInt) { "a" -> }`: a branch value of a built-in type the subject can never equal. */
internal fun KotlinSemanticChecks.incompatibleWhenConditions(w: KtWhenExpression, resolver: KotlinResolver): List<Diagnostic> {
    val subject = w.subjectExpression ?: return emptyList()
    if (subject is KtProperty) return emptyList()
    val st = resolver.inferType(subject) ?: return emptyList()
    if (st.nullable || st.isTypeParameter || canonicalName(st) !in FINAL_VALUE_TYPES) return emptyList()
    val sn = canonicalName(st)
    val out = ArrayList<Diagnostic>()
    for (entry in w.entries) for (c in entry.conditions) {
        val ce = (c as? KtWhenConditionWithExpression)?.expression ?: continue
        val ct = resolver.inferType(ce) ?: continue
        if (ct.nullable || ct.isTypeParameter) continue
        val cn = canonicalName(ct)
        if (cn == sn || cn !in FINAL_VALUE_TYPES) continue
        if (sn in NUMERIC && cn in NUMERIC) continue
        out += error(
            ce, "Incompatible types: ${renderType(ct)} and ${renderType(st)}", KotlinDiagnosticCodes.INCOMPARABLE_EQUALITY,
        )
    }
    return out
}

// --- lambdas ------------------------------------------------------------------------------------------------

/** The number of value parameters a lambda written for function type [t] takes, or null when [t] is not a
 *  plain Kotlin function type this can count (a `suspend` type read from bytecode carries a continuation). */
private fun KotlinSemanticChecks.lambdaArity(t: KotlinType?): Int? {
    if (t == null || t.isTypeParameter || !TypeRendering.isFunctionType(t.qualifiedName)) return null
    if (t.typeArguments.isEmpty()) return null
    if (t.typeArguments.any { it.qualifiedName == "kotlin.coroutines.Continuation" }) return null
    return t.typeArguments.size - 1 - (if (t.isExtensionFunctionType) 1 else 0)
}

private fun KotlinSemanticChecks.lambdaParameterDiagnostic(lambda: KtLambdaExpression, expected: Int): Diagnostic {
    val at: KtElement = lambda.functionLiteral.valueParameterList ?: lambda
    return error(
        at, "Expected $expected parameter${if (expected == 1) "" else "s"}, but the lambda declares ${lambda.valueParameters.size}",
        KotlinDiagnosticCodes.LAMBDA_PARAMETERS,
    )
}

/** `val f: (Int) -> String = { a, b -> … }` / `= { it }`: a lambda against the function type it is declared
 *  with: its parameter count, then the value its body ends on. */
internal fun KotlinSemanticChecks.declaredLambdaMismatch(declaredText: String?, init: KtExpression?, resolver: KotlinResolver): Diagnostic? {
    if (declaredText == null || init == null) return null
    val declared = service.typeFromText(declaredText, resolver.fileContext, enclosingClassFqnOf(init)) ?: return null
    return lambdaAgainstFunctionType(declared, init, resolver)
}

/**
 * A lambda (labeled or not) given where the function type [declared] is expected: its parameter count, then what it
 * returns, by its last statement and by each `return@label` aimed at it (RETURN_TYPE_MISMATCH). An assignment as the
 * last statement, and a `return@label` with no value, give `Unit`, which no other result type takes. `Function<R>`
 * has no parameter count to check but a result all the same.
 */
internal fun KotlinSemanticChecks.lambdaAgainstFunctionType(declared: KotlinType, init: KtExpression, resolver: KotlinResolver): Diagnostic? {
    var e = unwrapParen(init)
    var label: String? = null
    if (e is KtLabeledExpression) {
        label = e.getLabelName()
        e = e.baseExpression?.let { unwrapParen(it) } ?: return null
    }
    if (e is KtNamedFunction && e.nameIdentifier == null && label == null) return anonymousAgainstFunctionType(declared, e, resolver)
    val lambda = e as? KtLambdaExpression ?: return null
    if (declared.nullable) return null
    val arity = lambdaArity(declared)
    val plainFunction = canonicalName(declared) == "kotlin.Function" && declared.typeArguments.size == 1
    if (arity == null && !plainFunction) return null
    val n = lambda.valueParameters.size
    // A lambda written for a DECLARED extension function type may also name the receiver as its first parameter
    // (`val f: String.() -> String = { x -> x }`, KT-81115), so one more parameter than the type's is fine. Not so
    // for a call's argument, where it is a mismatch (KT-56138).
    if (arity != null && n > 0 && n != arity && !(declared.isExtensionFunctionType && n == arity + 1)) return lambdaParameterDiagnostic(lambda, arity)
    val result = declared.typeArguments.lastOrNull() as? KotlinType ?: return null
    if (result.isTypeParameter || result.projection == "*") return null
    val rn = canonicalName(result)
    // Unit coerces the last statement. A value handed back by `return@label` is checked against the lambda's return
    // type as it stands, each one on its own ([labeledReturnMismatch]).
    if (rn == UNIT) return null
    val concreteResult = !result.nullable && service.isKnownType(rn) && rn != NOTHING
    // A bare `return@label` gives `Unit`, which no other result type takes.
    if (label != null && concreteResult) {
        returnsTo(lambda, label).firstOrNull { it.returnedExpression == null }?.let { ret ->
            return error(ret, "Type mismatch: inferred type is Unit but ${renderType(result)} was expected", KotlinDiagnosticCodes.TYPE_MISMATCH)
        }
    }
    val last = lambda.bodyExpression?.statements?.lastOrNull()?.takeIf { it !is KtDeclaration } ?: return null
    if (last is KtReturnExpression) return null
    // An `if` with no `else` is a statement whose value is `Unit` (K2 types it so), whatever its branch holds.
    if (last is KtIfExpression && last.`else` == null) {
        if (concreteResult && rn != "kotlin.Any") {
            return error(last, "Type mismatch: inferred type is Unit but ${renderType(result)} was expected", KotlinDiagnosticCodes.TYPE_MISMATCH)
        }
        return null
    }
    if (last is KtBinaryExpression && last.operationToken in ASSIGNMENT_TOKENS) {
        if (concreteResult && rn != "kotlin.Any") {
            return error(last, "Type mismatch: inferred type is Unit but ${renderType(result)} was expected", KotlinDiagnosticCodes.TYPE_MISMATCH)
        }
        return null
    }
    return valueMismatch(result.withProjection(""), last, resolver)
}

/**
 * An anonymous function given where the function type [declared] is expected. Its parameters (receiver included)
 * must match in number and take what the type passes (INITIALIZER_TYPE_MISMATCH); an expression body is checked
 * against the expected result as it stands, with no coercion to `Unit` (RETURN_TYPE_MISMATCH), and a block body
 * without a written result type returns `Unit`.
 */
private fun KotlinSemanticChecks.anonymousAgainstFunctionType(declared: KotlinType, fn: KtNamedFunction, resolver: KotlinResolver): Diagnostic? {
    if (declared.nullable || lambdaArity(declared) == null) return null
    val expectedAll = declared.typeArguments.dropLast(1).map { it as? KotlinType ?: return null }
    val result = (declared.typeArguments.lastOrNull() as? KotlinType)?.withProjection("") ?: return null
    val ownReceiver = fn.receiverTypeReference
    val ownParams = listOfNotNull(ownReceiver) + fn.valueParameters.map { it.typeReference }
    fun mismatch(): Diagnostic = error(fn, "Type mismatch: this function is not a ${renderType(declared)}", KotlinDiagnosticCodes.TYPE_MISMATCH)
    if (ownParams.size != expectedAll.size) return mismatch()
    if ((ownReceiver != null) != declared.isExtensionFunctionType) return null
    val typing = KotlinStrictSubtyping(service)
    for ((i, ref) in ownParams.withIndex()) {
        val written = ref?.text ?: continue
        if ('(' in written || '@' in written) return null
        val p = service.typeFromText(written, resolver.fileContext, enclosingClassFqnOf(fn)) ?: return null
        val expected = expectedAll[i].withProjection("")
        if (typing.subtype(expected, p) == false) return mismatch()
    }
    if (result.isTypeParameter || !service.isKnownType(canonicalName(result))) return null
    val body = fn.bodyExpression ?: return null
    if (fn.hasBlockBody()) {
        val writtenResult = fn.typeReference?.text
        if (writtenResult == null) {
            // `Unit` is a subtype of `Unit`, `Any` and the nullable types only.
            val rn = canonicalName(result)
            return if (!result.nullable && rn != UNIT && rn != "kotlin.Any") mismatch() else null
        }
        val produced = service.typeFromText(writtenResult, resolver.fileContext, enclosingClassFqnOf(fn)) ?: return null
        return if (typing.subtype(produced, result) == false) mismatch() else null
    }
    if (isFunctionLiteral(body)) return lambdaAgainstFunctionType(result, body, resolver)
    return valueMismatch(result, body, resolver)
}

private val ASSIGNMENT_TOKENS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

/** The `return@label` expressions in [lambda] that return from it: its own body and the lambdas nested in it, not
 *  a nested function or class, nor a nested lambda that reuses the label. */
private fun returnsTo(lambda: KtLambdaExpression, label: String): List<KtReturnExpression> {
    val out = ArrayList<KtReturnExpression>()
    fun visit(el: KtElement) {
        if (el is KtNamedFunction || el is KtClassOrObject) return
        if (el is KtLabeledExpression && el.getLabelName() == label) return
        if (el is KtReturnExpression && el.getLabelName() == label) out += el
        var c = el.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    lambda.bodyExpression?.let(::visit)
    return out
}

/** `xs.forEach { a, b -> }`: a lambda argument whose parameter count fits NO overload of the call. Silent as
 *  soon as one candidate takes it, or one cannot be judged (a Java SAM, a type parameter, a `suspend` type). */
internal fun KotlinSemanticChecks.lambdaArgumentParameterCount(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val args = call.valueArguments
    val lambdas = args.withIndex().filter { (_, a) ->
        (a.getArgumentExpression()?.let { unwrapParen(it) } as? KtLambdaExpression)?.valueParameters?.isNotEmpty() == true
    }
    if (lambdas.isEmpty() || args.any { it.getSpreadElement() != null }) return null
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
    if (targets.isEmpty() || targets.any { it.kind != SymbolKind.METHOD }) return null
    for ((i, arg) in lambdas) {
        val lambda = unwrapParen(arg.getArgumentExpression()!!) as KtLambdaExpression
        val n = lambda.valueParameters.size
        var expected: Int? = null
        var judged = true
        for (c in targets) {
            // A scope function (`T.run { }`) is what a call falls back to when the member it meant is not in
            // the model; its shape says nothing about the lambda written for that member.
            if (c.varargParamIndex >= 0 || c.receiverTypeParam != null) { judged = false; break }
            val named = arg.getArgumentName()?.asName?.identifier
            val idx = when {
                named != null -> c.paramNames.indexOf(named)
                arg is KtLambdaArgument -> c.paramTypes.size - 1
                else -> i
            }
            val pt = c.paramTypes.getOrNull(idx) as? KotlinType
            // A Kotlin function type is counted directly; a Java functional interface by its one method.
            val arity = lambdaArity(pt) ?: pt?.takeIf { !it.isTypeParameter && service.isJavaType(it.qualifiedName) }
                ?.let { service.functionalShape(it) }?.takeIf { !it.isExtension }?.parameterTypes?.size
            if (arity == null || arity == n) { judged = false; break }
            expected = expected ?: arity
        }
        if (judged && expected != null) return lambdaParameterDiagnostic(lambda, expected)
    }
    return null
}

// --- visibility ---------------------------------------------------------------------------------------------

/** `import a.foo` of a top-level declaration that is `private` in another file of the project: a private
 *  top-level declaration is visible only inside its own file, so the import names nothing usable here. */
internal fun KotlinSemanticChecks.privateTopLevelImports(file: KtFile): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    val here = file.name.substringAfterLast('/')
    for (imp in file.importDirectives) {
        if (imp.isAllUnder) continue
        val fqn = imp.importedFqName?.asString()?.takeIf { '.' in it } ?: continue
        val files = service.privateTopLevelSourceFiles(fqn) ?: continue
        if (files.any { it.substringAfterLast('/') == here }) continue
        val ref = imp.importedReference ?: continue
        val leaf = (ref as? dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression)?.selectorExpression ?: ref
        val name = fqn.substringAfterLast('.')
        out += error(leaf, "Cannot access '$name': it is private in file", KotlinDiagnosticCodes.INVISIBLE_REFERENCE)
    }
    return out
}

/** A public top-level or member declaration whose signature names a `private` top-level class of this file
 *  (`private class P` + `fun make(): P`): callers elsewhere could not name the type. */
internal fun KotlinSemanticChecks.exposedPrivateType(decl: KtCallableDeclaration): Diagnostic? {
    if (decl !is KtNamedFunction && decl !is KtProperty) return null
    if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD) || decl.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    // A local declaration, or a member of a private or local class, is no more visible than its container.
    var node = decl.parent
    while (node != null) {
        when (node) {
            is KtBlockExpression, is KtFunctionLiteral, is KtPropertyAccessor -> return null
            is KtClassOrObject -> if (node.hasModifier(KtTokens.PRIVATE_KEYWORD) || node.name == null) return null
            else -> {}
        }
        node = node.parent
    }
    val file = decl.containingFile
    val privateTypes = file.declarations.filterIsInstance<KtClassOrObject>()
        .filter { it.hasModifier(KtTokens.PRIVATE_KEYWORD) }.mapNotNull { it.name }.toHashSet()
    if (privateTypes.isEmpty()) return null
    val refs = ArrayList<KtTypeReference>()
    decl.typeReference?.let { refs += it }
    decl.receiverTypeReference?.let { refs += it }
    (decl as? KtNamedFunction)?.valueParameters?.forEach { p -> p.typeReference?.let { refs += it } }
    // A type parameter or a nested class of the same name shadows the private one: only an unshadowed,
    // unqualified use names it.
    val shadowed = HashSet<String>()
    decl.typeParameters.forEach { tp -> tp.name?.let { shadowed += it } }
    var cls: KtElement? = decl.parent
    while (cls != null) {
        if (cls is KtClassOrObject) {
            cls.typeParameters.forEach { tp -> tp.name?.let { shadowed += it } }
            cls.declarations.filterIsInstance<KtClassOrObject>().forEach { n -> n.name?.let { shadowed += it } }
        }
        cls = cls.parent
    }
    for (ref in refs) {
        val hit = firstUserType(ref) { ut ->
            ut.qualifier == null && ut.referenceExpression?.getReferencedName()?.let { it in privateTypes && it !in shadowed } == true
        } ?: continue
        val name = hit.referenceExpression?.getReferencedName()
        val what = if (decl is KtNamedFunction) "function" else "property"
        return error(hit, "A non-private $what exposes the private-in-file type '$name'", KotlinDiagnosticCodes.EXPOSED_VISIBILITY)
    }
    return null
}

private fun firstUserType(root: KtElement, match: (KtUserType) -> Boolean): KtUserType? {
    if (root is KtUserType && match(root)) return root
    var c = root.firstChild
    while (c != null) {
        firstUserType(c, match)?.let { return it }
        c = c.nextSibling
    }
    return null
}

// --- assignment ---------------------------------------------------------------------------------------------

/** Whether a source property declaration is a `val` (a `val` constructor parameter counts). */
private fun isValDeclaration(psi: KtElement?): Boolean = when (psi) {
    is KtProperty -> !psi.isVar
    is KtParameter -> psi.hasValOrVar() && psi.valOrVarKeyword?.text == "val"
    else -> false
}

/**
 * `s.length = 3`, `user.name = "x"` where `name` is a `val`, and a bare `x = 1` inside a method of the class
 * that declares `val x`. A source property is judged from its declaration; a built-in one from a short list.
 * An assignment in a constructor or initializer is the property's initialization and is left alone.
 */
internal fun KotlinSemanticChecks.valMemberReassignment(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken in VAL_COMPOUND_ASSIGN) return valCompoundReassignment(e, resolver)
    if (e.operationToken != KtTokens.EQ) return null
    when (val left = e.left) {
        is KtDotQualifiedExpression, is KtSafeQualifiedExpression -> {
            left as KtQualifiedExpression
            val selector = left.selectorExpression as? KtNameReferenceExpression ?: return null
            val name = selector.getReferencedName()
            val receiver = left.receiverExpression
            if (resolver.isTypeReceiver(receiver)) return null
            val inferred = resolver.inferType(receiver) ?: return null
            val recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return null
            if (recvType.isTypeParameter) return null
            val rn = canonicalName(recvType)
            if (BUILTIN_READ_ONLY[rn]?.contains(name) == true) {
                return error(selector, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
            }
            if (!service.isSourceClass(recvType.qualifiedName)) return null
            val members = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name).filter { it.name == name }
            if (members.isEmpty() || members.all { it.isExtension }) {
                // `some.y = …` where `y` is a `val` extension property: it has no field, and no setter.
                val extVals = members.isNotEmpty() && members.all { m ->
                    (m.sourcePsi() as? KtProperty)?.let { !it.isVar && it.receiverTypeReference != null } == true
                }
                return if (extVals || extensionValNamed(resolver, name, recvType.qualifiedName)) {
                    error(selector, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
                } else null
            }
            if (members.any { it.isExtension || it.kind != SymbolKind.FIELD || !isValDeclaration(it.sourcePsi()) }) return null
            if (resolver.scopeMemberExtensions(selector.textRange.startOffset, recvType, name).any { it.name == name }) return null
            // `this.x = …` in a constructor or initializer of the class declaring `x` initializes it, when it
            // was declared without a value. Through `next?.x` or any other instance it never does.
            val r = unwrapParen(receiver)
            if (left is KtDotQualifiedExpression && r is KtThisExpression && r.getLabelName() == null &&
                members.all { deferredMemberVal(it.sourcePsi()) && insideInitializationOf(e, declaringClass(it.sourcePsi())) }
            ) return null
            return error(selector, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
        }
        is KtNameReferenceExpression -> return bareNameValReassignment(left, e, resolver)
        else -> return null
    }
}

/**
 * An assignment ([at]) to the bare name [left] that reaches a `val` that is not a local: a member `val` with a
 * value (or one declared without, outside its own class's constructor), a `val` constructor property, a `val`
 * extension property of an implicit receiver, or a top-level `val`.
 */
private fun KotlinSemanticChecks.bareNameValReassignment(left: KtNameReferenceExpression, at: KtElement, resolver: KotlinResolver): Diagnostic? {
    val e = at
    val name = left.getReferencedName()
    val offset = left.textRange.startOffset
    if (resolver.localsAt(offset, name).isNotEmpty()) return null
    if (nearestLocalDecl(name, offset, left) != null) return null
    val member = resolver.implicitReceiverMember(name, offset)
    if (member != null) {
        if (member.kind != SymbolKind.FIELD) return null
        if (member.isExtension) {
            // A `val` extension property has no field and no setter.
            val psi = member.sourcePsi() as? KtProperty ?: return null
            return if (!psi.isVar && psi.receiverTypeReference != null) error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN) else null
        }
        return when (val psi = member.sourcePsi()) {
            // A `val` constructor property already holds the argument.
            is KtParameter -> if (isValDeclaration(psi)) error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN) else null
            is KtProperty -> when {
                psi.isVar || psi.isLocal -> null
                hasValue(psi) ->
                    error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
                // Declared without a value: initialized by the constructor of its own class, and assigned
                // nowhere else (a member function, an accessor, another class's initializer).
                insideInitializationOf(e, declaringClass(psi)) -> null
                else -> error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
            }
            else -> null
        }
    }
    // A top-level `val` with a value, or a `val` extension property of an implicit receiver.
    val tops = service.topLevelByName(name).filter { it.name == name }
    if (tops.isEmpty() || tops.any { it.kind != SymbolKind.FIELD }) return null
    val receivers = resolver.implicitReceiversAt(offset).map { it.qualifiedName.substringAfterLast('.') }
    val allVals = tops.all { t ->
        val psi = t.sourcePsi() as? KtProperty ?: return@all false
        if (psi.isVar) return@all false
        // One declared without a value is initialized by a top-level initializer, never by a function.
        if (!(hasValue(psi))) {
            return@all psi.receiverTypeReference == null &&
                e.parents.takeWhile { it !is KtFile }.any { it is KtNamedFunction || it is KtPropertyAccessor }
        }
        val recv = psi.receiverTypeReference?.text ?: return@all true
        recv.substringBefore('<').removeSuffix("?").substringAfterLast('.').trim() in receivers
    }
    return if (allVals) error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN) else null
}

/** `a++` / `--a` on a `val` that is not a local (see [bareNameValReassignment]). */
internal fun KotlinSemanticChecks.valMemberIncDec(e: KtUnaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken != KtTokens.PLUSPLUS && e.operationToken != KtTokens.MINUSMINUS) return null
    val base = unwrapParen(e.baseExpression ?: return null) as? KtNameReferenceExpression ?: return null
    return bareNameValReassignment(base, e, resolver)
}

private val VAL_COMPOUND_ASSIGN = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

/** The types whose `+=` and the like can only mean `x = x + y`: they declare no `plusAssign` family operator. */
private val NO_ASSIGN_OPERATOR_TYPES = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float", "kotlin.Char",
    "kotlin.String", "kotlin.Boolean", "kotlin.UInt", "kotlin.ULong",
)

/**
 * `x += 1` on a `val` whose type has no `plusAssign` (a number, a `String`): the operator can only be read as
 * `x = x + 1`, a reassignment. A local `val`, a parameter (a `for` variable included), or a member `val`.
 */
private fun KotlinSemanticChecks.valCompoundReassignment(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val left = unwrapParen(e.left ?: return null) as? KtNameReferenceExpression ?: return null
    val name = left.getReferencedName()
    val offset = left.textRange.startOffset
    val local = nearestLocalDecl(name, offset, left)
    val isVal = when (local) {
        is KtProperty -> !local.isVar && (local.hasInitializer() || local.hasDelegate())
        is KtParameter -> !local.hasValOrVar()
        null -> {
            if (resolver.localsAt(offset, name).isNotEmpty()) return null
            val member = resolver.implicitReceiverMember(name, offset) ?: return null
            if (member.kind != SymbolKind.FIELD || member.isExtension) return null
            val psi = member.sourcePsi()
            (psi is KtProperty && !psi.isVar && (hasValue(psi))) ||
                (psi is KtParameter && isValDeclaration(psi))
        }
        else -> false
    }
    if (!isVal) return null
    val t = resolver.inferType(left) ?: return null
    if (t.isTypeParameter || canonicalName(t) !in NO_ASSIGN_OPERATOR_TYPES) return null
    return error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
}

/** Whether a source `val` extension property named [name] exists for a receiver named like [receiverFqn]. */
private fun KotlinSemanticChecks.extensionValNamed(resolver: KotlinResolver, name: String, receiverFqn: String): Boolean {
    val tops = service.topLevelByName(name).filter { it.name == name }
    if (tops.isEmpty()) return false
    val simple = receiverFqn.substringAfterLast('.')
    return tops.all { t ->
        val psi = t.sourcePsi() as? KtProperty ?: return@all false
        val recv = psi.receiverTypeReference?.text ?: return@all false
        !psi.isVar && recv.substringBefore('<').removeSuffix("?").substringAfterLast('.').trim() == simple
    }
}

/** Whether a `val` holds a value of its own: an initializer, a delegate, or a getter that reads no field. */
private fun hasValue(psi: KtProperty): Boolean =
    psi.hasInitializer() || psi.hasDelegate() || (psi.getter != null && !hasBackingField(psi))

/** A member `val` declared without a value (initialized by the constructor). */
private fun deferredMemberVal(psi: KtElement?): Boolean =
    psi is KtProperty && !psi.isVar && !hasValue(psi)

private fun declaringClass(psi: KtElement?): KtClassOrObject? = (psi?.parent as? KtClassBody)?.parent as? KtClassOrObject

/**
 * Whether [e] runs as part of constructing an instance of [cls]: in one of its `init` blocks, its secondary
 * constructors or its property initializers, and not inside a function, an accessor or another class there.
 */
private fun insideInitializationOf(e: KtElement, cls: KtClassOrObject?): Boolean {
    if (cls == null) return false
    var node = e.parent
    while (node != null) {
        when (node) {
            is KtNamedFunction, is KtPropertyAccessor -> return false
            is KtSecondaryConstructor -> return node.getStrictParentOfType<KtClassOrObject>() == cls
            is KtClassOrObject -> return node == cls
            is KtFile -> return false
            else -> {}
        }
        node = node.parent
    }
    return false
}

private fun insideInitialization(e: KtElement): Boolean {
    var node = e.parent
    while (node != null) {
        when (node) {
            is KtNamedFunction, is KtPropertyAccessor -> return false
            is KtSecondaryConstructor -> return true
            is KtClassBody -> return true // reached the class body without crossing a function: an `init` block
            is KtFile -> return true
            else -> {}
        }
        node = node.parent
    }
    return true
}

/** `list[0] = x` on a read-only collection or a String: there is no `set` to call. */
internal fun KotlinSemanticChecks.indexedAssignmentWithoutSet(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken != KtTokens.EQ) return null
    val left = e.left as? KtArrayAccessExpression ?: return null
    val base = left.arrayExpression ?: return null
    val inferred = resolver.inferType(base) ?: return null
    val t = resolver.receiverForMembers(inferred, base.textRange.startOffset) ?: return null
    if (t.isTypeParameter || canonicalName(t) !in READ_ONLY_INDEXED) return null
    if (hasOperator(t, "set", base, resolver)) return null
    return error(left, "No 'set' operator method providing array access on ${renderType(t)}", KotlinDiagnosticCodes.NO_SET_OPERATOR)
}

// --- supertypes ---------------------------------------------------------------------------------------------

/**
 * A supertype's constructor call: `: Iface()` on an interface (which has none), and `: Base()` whose
 * argument count fits no constructor of a `Base` declared in this file. The count is read from the
 * declaration, defaults and all; a vararg constructor or a named argument backs off.
 */
internal fun KotlinSemanticChecks.supertypeConstructorCall(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    for (entry in cls.superTypeListEntries) {
        if (entry !is KtSuperTypeCallEntry) continue
        val userType = entry.typeReference?.typeElement as? KtUserType ?: continue
        if (userType.qualifier != null) continue
        val name = userType.referenceExpression?.getReferencedName() ?: continue
        if (resolver.isTypeParameterInScope(name, entry.textRange.startOffset) || service.isProjectTypeAlias(name)) continue
        val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(cls)) ?: continue
        if (service.isInterfaceType(fqn) == true) {
            out += error(
                entry.valueArgumentList ?: entry, "Interface '$name' does not have constructors",
                KotlinDiagnosticCodes.NO_CONSTRUCTOR,
            )
            continue
        }
        if (entry.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) continue
        val target = sameFileSuperclass(cls, name) ?: continue
        if (target.fqName?.asString() != fqn || target.isEnum() || target.isAnnotation()) continue
        val ctors = constructorParameterLists(target)
        if (ctors.any { params -> params.any { it.isVarArg } }) continue
        val n = entry.valueArguments.size
        if (ctors.any { n in it.count { p -> !p.hasDefaultValue() }..it.size }) continue
        val arities = ctors.flatMap { it.count { p -> !p.hasDefaultValue() }..it.size }.distinct().sorted().joinToString("/")
        out += error(
            entry.valueArgumentList ?: entry, "No constructor of '$name' takes $n argument(s) (expected $arities)",
            KotlinDiagnosticCodes.CONSTRUCTOR_ARGS,
        )
    }
    return out
}

/** The class named [name] among this file's top-level declarations, when it is the only one. */
private fun sameFileSuperclass(from: KtClassOrObject, name: String): KtClass? =
    from.containingFile.declarations.filterIsInstance<KtClass>().filter { it.name == name }.singleOrNull()

// --- calls ---------------------------------------------------------------------------------------------------

/**
 * `f(null)` where every overload of `f` declares that parameter non-null. The `null` literal has no type, so
 * the applicability check never saw it. Java callees are left alone (a platform type takes null), as is a
 * type-parameter slot (`T` may be nullable).
 */
internal fun KotlinSemanticChecks.nullArgumentForNonNull(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val args = call.valueArguments
    if (args.none { a -> a.getArgumentExpression()?.let { isNullLiteral(it) } == true }) return null
    if (args.any { it.getSpreadElement() != null }) return null
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
    if (targets.isEmpty() || targets.any { it.kind != SymbolKind.METHOD }) return null
    args.forEachIndexed { i, arg ->
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        if (arg is KtLambdaArgument || !isNullLiteral(expr)) return@forEachIndexed
        var expected: KotlinType? = null
        for (c in targets) {
            val named = arg.getArgumentName()?.asName?.identifier
            val idx = if (named != null) c.paramNames.indexOf(named) else i
            if (idx < 0 || (c.varargParamIndex in 0..idx)) return null
            // Each `null` argument is judged on its own: one passed for a parameter that may take it (`x: T`) says
            // nothing about the next (`y: T & Any`).
            if (!plainNonNullSourceParameter(c, idx)) return@forEachIndexed
            val pt = c.paramTypes.getOrNull(idx) as? KotlinType ?: return@forEachIndexed
            // `T & Any` refuses `null` whatever `T` is bound to; a plain `T` may be nullable.
            if (pt.nullable || !pt.definitelyNonNull && (pt.isTypeParameter || !service.isKnownType(canonicalName(pt)))) return@forEachIndexed
            expected = expected ?: pt
        }
        expected?.let { return nullForNonNull(it, expr) }
    }
    return null
}

/** `A.m()` where `m` is an instance member of the source class `A`: there is no instance to call it on. */
internal fun KotlinSemanticChecks.instanceMemberViaType(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    val receiver = when {
        parent is KtQualifiedExpression && parent.selectorExpression === ref -> parent.receiverExpression
        parent is KtCallExpression && parent.calleeExpression === ref ->
            (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
        else -> null
    } ?: return null
    if (unwrapParen(receiver) !is KtNameReferenceExpression || !resolver.isTypeReceiver(receiver)) return null
    val name = ref.getReferencedName()
    if (name.isEmpty() || name in setOf("values", "valueOf", "entries")) return null
    val inferred = resolver.inferType(receiver) ?: return null
    if (inferred.isTypeParameter || !service.isSourceClass(inferred.qualifiedName)) return null
    val fqn = inferred.qualifiedName
    if (service.isKnownType("$fqn.$name")) return null
    if (service.companionMembersFor(fqn, name).any { it.name == name }) return null
    val members = service.membersNamedForCheck(fqn, inferred.typeArguments, name).filter { it.name == name }
    if (members.isEmpty()) return null
    // Every candidate must be an ordinary member declared in a class body of this project: not an
    // extension, not a Java static, not a member of an object or companion.
    val allInstance = members.all { m ->
        if (m.isExtension || Modifier.STATIC in m.modifiers) return@all false
        val psi = m.sourcePsi() ?: return@all false
        val owner = when (psi) {
            is KtParameter -> psi.getStrictParentOfType<KtClassOrObject>()
            else -> (psi.parent as? KtClassBody)?.parent as? KtClassOrObject
        }
        owner is KtClass && !owner.isEnum()
    }
    if (!allInstance) return null
    if (resolver.scopeMemberExtensions(ref.textRange.startOffset, inferred, name).any { it.name == name }) return null
    return error(
        ref, "Unresolved reference: $name ('${fqn.substringAfterLast('.')}.$name' needs an instance)",
        KotlinDiagnosticCodes.INSTANCE_ACCESS,
    )
}

/** `Obj()` on a source `object` with no `invoke` operator. */
internal fun KotlinSemanticChecks.objectInvoked(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val parent = call.parent
    if (parent is KtQualifiedExpression && parent.selectorExpression === call) return null
    val name = callee.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    if (resolver.localsAt(callee.textRange.startOffset, name).isNotEmpty()) return null
    val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(callee)) ?: return null
    if (!service.isSourceClass(fqn) || !service.isSingletonObject(fqn)) return null
    if (runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).isNotEmpty()) return null
    val type = service.typeByFqn(fqn)
    if (isInvocable(type)) return null
    return error(
        callee, "Expression '$name' of type ${renderType(type)} cannot be invoked as a function",
        KotlinDiagnosticCodes.NOT_CALLABLE,
    )
}

// --- type arguments -----------------------------------------------------------------------------------------

/**
 * `val a: Box = …` where `class Box<T>`: a generic class named with no type arguments in a declaration's type
 * (a property, parameter or return type, or an extension receiver), or anywhere as another type's ARGUMENT
 * (`x is List<Map>`, `f<Box>()`). A top-level `is`/`as` target, a supertype, a callable reference and an
 * annotation are places where the arguments may legitimately be absent or inferred, so those are left alone.
 */
internal fun KotlinSemanticChecks.missingTypeArguments(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
    if (userType.typeArgumentList != null || userType.qualifier != null || userType.parent is KtUserType) return null
    val name = userType.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return null
    // Climb out of the type; what holds it decides whether arguments are required.
    var node: KtElement = userType
    var topRef: KtTypeReference? = null
    var asArgument = false
    while (true) {
        val p = node.parent ?: return null
        if (p is KtTypeReference) topRef = p
        if (p is KtTypeProjection) asArgument = true
        if (p is KtDeclaration || p is KtExpression || p is KtSuperTypeEntry || p is KtSuperTypeCallEntry ||
            p is KtAnnotationEntry || p is KtFile
        ) { node = p; break }
        node = p
    }
    val holder = node
    val inDeclarationType = when (holder) {
        is KtTypeAlias -> false
        is KtProperty -> holder.typeReference === topRef || holder.receiverTypeReference === topRef
        is KtParameter -> holder.typeReference === topRef && holder.parent?.parent !is KtCatchClause
        is KtNamedFunction -> holder.typeReference === topRef || holder.receiverTypeReference === topRef
        is KtAnnotationEntry -> false
        else -> asArgument
    }
    if (!inDeclarationType) return null
    if (service.isProjectTypeAlias(name) || resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return null
    if (resolver.localTypesInScope(userType.textRange.startOffset).containsKey(name)) return null
    val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(userType)) ?: return null
    // A library class counts when its parameters are decoded (or are the builtins' fixed ones) and the name is
    // its own: a library typealias resolves to its expansion, whose parameters are not the alias's.
    val expected = when {
        service.isSourceClass(fqn) -> service.classTypeParameters(fqn).size
        fqn.substringAfterLast('.') != name || !service.isKnownType(fqn) -> return null
        else -> service.classTypeParameters(fqn).size.takeIf { it > 0 } ?: builtinTypeArity(fqn) ?: return null
    }
    if (expected == 0) return null
    return error(
        userType, "$expected type argument${if (expected == 1) "" else "s"} expected for '$name'",
        KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT,
    )
}

/** The vararg rule [KotlinSemanticChecks] applies to an argument landing in a `vararg` slot with no spread:
 *  an array where one ELEMENT of a built-in value type is expected (`f(intArrayOf(1))` for `vararg a: Int`). */
internal fun KotlinSemanticChecks.arrayForVarargElement(elementType: KotlinType, actual: KotlinType): Boolean {
    if (elementType.isTypeParameter || actual.isTypeParameter) return false
    if (canonicalName(elementType) !in FINAL_VALUE_TYPES) return false
    val a = canonicalName(actual)
    return a == "kotlin.Array" || (a.startsWith("kotlin.") && a.endsWith("Array") && a != "kotlin.Array")
}

/** An array given BY NAME to a vararg of [elementType] whose elements cannot be that type (`a = ints` for
 *  `vararg a: String`). */
internal fun KotlinSemanticChecks.namedVarargArrayMismatch(elementType: KotlinType, actual: KotlinType): Boolean {
    if (elementType.isTypeParameter || actual.isTypeParameter) return false
    val a = canonicalName(actual)
    val e = canonicalName(elementType)
    val primitive = PRIMITIVE_ARRAYS[a]
    if (primitive != null) return e in PRIMITIVE_ARRAYS.values && e != primitive
    if (a != "kotlin.Array") return false
    val arg = (actual.typeArguments.singleOrNull() as? KotlinType)?.withProjection("") ?: return false
    if (arg.isTypeParameter || canonicalName(arg) == "*") return false
    return isMismatch(elementType, arg)
}

private val PRIMITIVE_ARRAYS = mapOf(
    "kotlin.IntArray" to "kotlin.Int", "kotlin.LongArray" to "kotlin.Long", "kotlin.ShortArray" to "kotlin.Short",
    "kotlin.ByteArray" to "kotlin.Byte", "kotlin.DoubleArray" to "kotlin.Double", "kotlin.FloatArray" to "kotlin.Float",
    "kotlin.CharArray" to "kotlin.Char", "kotlin.BooleanArray" to "kotlin.Boolean",
)

/** A known, non-array value given BY NAME to a vararg of [elementType]: the named form takes the whole array. */
internal fun KotlinSemanticChecks.singleElementForNamedVararg(elementType: KotlinType, actual: KotlinType): Boolean {
    // The element type does not matter, a type parameter included: the named form takes an `Array<out E>`, which a
    // known non-array value never is.
    if (actual.isTypeParameter) return false
    fun isArray(n: String) = n == "kotlin.Array" || (n.startsWith("kotlin.") && n.endsWith("Array"))
    if (!elementType.isTypeParameter && isArray(canonicalName(elementType))) return false
    val a = canonicalName(actual)
    if (a == NOTHING || isArray(a) || !service.isKnownType(a)) return false
    return true
}

/** `*value` where [actual] is a known type that is no array at all (`*"2"`): only an array can be spread. */
internal fun KotlinSemanticChecks.spreadOfNonArray(actual: KotlinType): Boolean {
    if (actual.isTypeParameter) return false
    val a = canonicalName(actual)
    if (a == NOTHING || a == "kotlin.Any" || !service.isKnownType(a)) return false
    if (a == "kotlin.Array" || (a.startsWith("kotlin.") && a.endsWith("Array"))) return false
    // A Java array or a collection-like platform type may surface under its JVM name.
    if (a.startsWith("java.") || a.endsWith("[]") || a == "kotlin.Cloneable") return false
    // `Array` is final and implements nothing else a value could be declared as, so any other known type is no array.
    return true
}

// --- non-local returns ----------------------------------------------------------------------------------------

/**
 * Whether [lambda] is certainly NOT inlined into its caller, so a `return` cannot pass through it: a lambda
 * that is stored, returned or invoked as a value rather than passed to a call, one passed to a constructor,
 * or one passed to a project function that is not `inline` (or is, but takes it `noinline`, `crossinline`, as
 * a nullable function type, or as a type parameter). False whenever that is not certain, a library callee
 * included: a library function's metadata may not say.
 */
internal fun KotlinSemanticChecks.lambdaNotInlined(lambda: KtLambdaExpression, resolver: KotlinResolver?): Boolean {
    var holder: KtElement = lambda
    while (true) {
        val p = holder.parent ?: return false
        if (p is KtParenthesizedExpression || p is KtLabeledExpression || p is KtAnnotatedExpression) holder = p else break
    }
    when (val p = holder.parent) {
        // Stored or handed back as a value: nothing inlines it.
        is KtProperty -> return p.initializer === holder
        is KtPropertyDelegate, is KtReturnExpression, is KtBlockExpression -> return true
        is KtNamedFunction -> return p.bodyExpression === holder
        is KtBinaryExpression -> return p.operationToken == KtTokens.EQ && p.right === holder
        // `{ ... }()` invokes a function value, and `{ ... }.let { }` passes one along as a receiver.
        is KtCallExpression -> return p.calleeExpression === holder
        is KtQualifiedExpression -> return p.receiverExpression === holder
        // A trailing lambda is a value argument too, so it is matched first.
        is KtLambdaArgument -> {}
        is KtValueArgument -> when ((p.parent as? KtValueArgumentList)?.parent) {
            is KtSuperTypeCallEntry, is KtConstructorDelegationCall -> return true
            is KtCallExpression -> {}
            else -> return false
        }
        else -> return false
    }
    if (resolver == null || holder !== lambda) return false
    val (call, argIndex) = resolver.enclosingCallAndParamIndex(lambda) ?: return false
    val callee = resolver.resolveCalleeFunction(call) ?: return false
    if (!callee.origin.fromSource) return false
    val psi = (callee.declaration() as? KotlinDomNode)?.psi
    if (callee.kind == SymbolKind.CONSTRUCTOR) return psi is KtClass || psi is KtPrimaryConstructor || psi is KtSecondaryConstructor
    val fn = psi as? KtNamedFunction ?: return false
    if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) {
        // A bare call could also reach an `inline` overload declared elsewhere; let any such overload win. One the
        // file reaches only through a star or default import does not: a function of the file's own package that
        // takes the call comes first (`fun <T> run(f: () -> T)` beside the stdlib's inline `run`).
        val name = fn.name ?: return false
        val file = call.containingFile
        val samePackage = fn.parent is KtFile && fn.containingFile.packageFqName.asString() == file.packageFqName.asString()
        if (call.parent !is KtQualifiedExpression &&
            service.topLevelByName(name).any { s ->
                s.kind == SymbolKind.METHOD && s.isInline && (!samePackage || s.packageName == file.packageFqName.asString() ||
                    file.importDirectives.any { !it.isAllUnder && it.importedFqName?.asString() == "${s.packageName}.$name" })
            }) return false
        return true
    }
    val param = fn.valueParameters.getOrNull(resolver.lambdaParamIndex(call, argIndex, callee)) ?: return false
    if (param.hasModifier(KtTokens.NOINLINE_KEYWORD) || param.hasModifier(KtTokens.CROSSINLINE_KEYWORD)) return true
    return when (val type = param.typeReference?.typeElement) {
        is KtNullableType -> true
        is KtUserType -> type.referenceExpression?.getReferencedName()?.let { n -> fn.typeParameters.any { it.name == n } } == true
        else -> false
    }
}

// --- overrides ----------------------------------------------------------------------------------------------

private fun mentionsJavaObject(t: KotlinType): Boolean =
    t.qualifiedName == "java.lang.Object" || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsJavaObject) != false }

private fun mentionsTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsTypeParameter) != false }

/**
 * An `override` whose declared type some overridden member does not accept (`override fun f(): String` over
 * `fun f(): Int`): as in K2's `FirOverrideChecker`, the type must be a subtype of EVERY overridden member's, so
 * `override fun f(): Z` over a `fun f(): W` is wrong even where a further-up `fun f(): X` would take a `Z`. Each
 * candidate's type must be concrete: one that mentions a type parameter is spelled differently in the subclass
 * than in the supertype, and is not compared.
 */
internal fun KotlinSemanticChecks.overrideReturnTypeMismatch(
    member: KtCallableDeclaration, candidates: List<KotlinSymbol>, resolver: KotlinResolver,
): Diagnostic? {
    if (member is KtProperty && member.isVar) return null // a `var` is invariant in ways not modeled here
    val typeRef = member.typeReference ?: return null
    val declared = service.typeFromText(typeRef.text, resolver.fileContext, enclosingClassFqnOf(member)) ?: return null
    if (mentionsTypeParameter(declared)) return null
    var expected: KotlinType? = null
    for (c in candidates) {
        val superType = c.type as? KotlinType ?: return null
        if (mentionsTypeParameter(superType) || c.typeParameters.isNotEmpty()) return null
        // A Java member read without its type arguments (`Iterator<Object>` for `Iterator<E>`) is erased, not concrete.
        if (mentionsJavaObject(superType)) return null
        if (isMismatch(superType, declared)) expected = expected ?: superType
    }
    expected ?: return null
    val what = if (member is KtNamedFunction) "Return type" else "Type"
    return error(
        typeRef, "$what of '${member.name}' is not a subtype of the overridden member's: ${renderType(expected)} expected",
        KotlinDiagnosticCodes.OVERRIDE_RETURN_TYPE,
    )
}

// --- what a type can be trusted for ---------------------------------------------------------------------------

internal fun isLiteral(e: KtExpression): Boolean = when (val u = unwrapParen(e)) {
    is KtConstantExpression, is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression -> true
    is KtPrefixExpression -> u.baseExpression?.let { unwrapParen(it) } is KtConstantExpression
    else -> false
}

/** The type written on the declaration of the local or parameter [ref] names, without `?`; null when [ref]
 *  is not a local, or its declaration leaves the type to inference. */
internal fun KotlinSemanticChecks.explicitTypeTextOf(ref: KtNameReferenceExpression): String? {
    val decl = nearestLocalDecl(ref.getReferencedName(), ref.textRange.startOffset, ref) ?: return null
    val typeRef = when (decl) {
        is KtProperty -> decl.typeReference
        is KtParameter -> decl.typeReference
        else -> null
    } ?: return null
    return typeRef.text.trim().removeSuffix("?").trim()
}

/**
 * Whether [e]'s type is a fact no smart cast can change: a literal, a number expression, or a local or
 * parameter whose written type is a final class (a cast can only narrow it to itself).
 */
internal fun KotlinSemanticChecks.typeReadOffTheTree(e: KtExpression, resolver: KotlinResolver): Boolean {
    val u = unwrapParen(e)
    if (isLiteral(u)) return true
    if (u !is KtNameReferenceExpression) return false
    val text = explicitTypeTextOf(u) ?: return false
    val decl = nearestLocalDecl(u.getReferencedName(), u.textRange.startOffset, u)
    if (decl is KtProperty && decl.isVar) return false
    if ((decl as? KtProperty)?.typeReference?.text?.trim()?.endsWith("?") == true) return false
    if ((decl as? KtParameter)?.typeReference?.text?.trim()?.endsWith("?") == true) return false
    if ('<' in text) return false
    val fqn = service.resolveTypeName(text, resolver.fileContext) ?: return false
    if (service.isFinalType(fqn) == true) return true
    // An open declared type is read off the tree too where no check written before the use narrows the value: the
    // flow walk proves no `is`/`as` target for it there (`when (x) { is Int -> … }` does not reach past the `when`).
    return service.isKnownType(fqn) && resolver.flowProvenTypes(u, deep = true).isEmpty()
}

/** A literal, or a local/parameter with a written type: an expression whose type is read, not inferred. */
internal fun KotlinSemanticChecks.plainlyTyped(e: KtExpression): Boolean {
    val u = unwrapParen(e)
    return isLiteral(u) || (u is KtNameReferenceExpression && explicitTypeTextOf(u) != null)
}

/**
 * Whether [ref] is the FIRST use of a nullable `val` or parameter since it was declared.
 *
 * A nullable local stops being nullable in more ways than a flow analysis can list: `if (x != null)`,
 * `x ?: return`, `requireNotNull(x)`, a test helper with a contract (`assertNotNull(x)`), `x?.name == "a"`.
 * Every one of them mentions the name. So the one place its declared nullability is certain is before any
 * other mention, which is also where the mistake this is after gets made: a nullable value handed straight
 * to something that needs a non-null one. A `val` initialized with a non-null value is smart-cast by that.
 */
internal fun KotlinSemanticChecks.firstUseOfNullableLocal(ref: KtNameReferenceExpression, resolver: KotlinResolver): Boolean {
    val name = ref.getReferencedName()
    val decl = nearestLocalDecl(name, ref.textRange.startOffset, ref) ?: return false
    when (decl) {
        is KtProperty -> {
            if (decl.isVar || decl.hasDelegate()) return false
            // The `?` must be WRITTEN: an inferred nullable type is the flow analysis's opinion again.
            if (decl.typeReference?.text?.trim()?.endsWith("?") != true) return false
            val init = decl.initializer
            if (init != null && !isNullLiteral(init) && resolver.inferType(init)?.nullable != true) return false
        }
        is KtParameter -> if (decl.isVarArg || decl.typeReference?.text?.trim()?.endsWith("?") != true) return false
        else -> return false
    }
    val from = (decl as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.nameIdentifier?.textRange?.endOffset ?: return false
    val to = ref.textRange.startOffset
    if (to <= from) return false
    val between = ref.containingFile.sourceText.subSequence(from, to)
    return !Regex("(?<![A-Za-z0-9_`])" + Regex.escape(name) + "(?![A-Za-z0-9_`])").containsMatchIn(between)
}

/**
 * A local or parameter WRITTEN with a nullable type, read where the null-flow pass finds no guard that makes it
 * non-null: the same authority the unsafe-call check takes for a receiver (`smartCastNonNull` follows if/else,
 * early exits, `&&`/`||`, `!!`, `is`, loops and, for a `var`, reassignment). A scope-function guard it does not
 * follow (`x?.let { }`) still backs off.
 */
internal fun KotlinSemanticChecks.flowNullableLocal(ref: KtNameReferenceExpression, resolver: KotlinResolver): Boolean {
    val name = ref.getReferencedName()
    val decl = nearestLocalDecl(name, ref.textRange.startOffset, ref)
    when (decl) {
        is KtProperty -> {
            // A `var` is smart-cast by whatever it was last assigned, and a `val` initialized later by its
            // assignments; only a `val` born nullable keeps its written type.
            if (decl.isVar || decl.hasDelegate() || decl.typeReference?.text?.trim()?.endsWith("?") != true) return false
            val init = decl.initializer ?: return false
            if (!isNullLiteral(init) && resolver.inferType(init)?.nullable != true) return false
        }
        is KtParameter -> if (decl.isVarArg || decl.typeReference?.text?.trim()?.endsWith("?") != true) return false
        else -> return false
    }
    if (resolver.smartCastNonNull(ref)) return false
    if (resolver.flowProvenTypes(ref, deep = true).any { !it.nullable }) return false
    // A `x!!` the flow pass does not follow (inside an object's initializer, a condition chain) still smart-casts.
    // One that runs only sometimes does not: inside a safe call's selector (`a?.f(x!!)` runs it only when `a` is
    // non-null), or inside one branch of an `if`/`when`, whose dominance the flow pass has already decided.
    val from = (decl as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).nameIdentifier?.textRange?.endOffset ?: return false
    val to = ref.textRange.startOffset
    var unconditional = false
    fun visit(e: KtElement) {
        if (unconditional || e.textRange.endOffset <= from || e.textRange.startOffset >= to) return
        if (e is KtPostfixExpression && e.operationToken == KtTokens.EXCLEXCL &&
            (e.baseExpression?.let { unwrapParen(it) } as? KtNameReferenceExpression)?.getReferencedName() == name &&
            !conditionallyRun(e)
        ) { unconditional = true; return }
        var c = e.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    visit(ref.getStrictParentOfType<KtNamedFunction>() ?: ref.containingFile)
    if (unconditional) return false
    return !hasUnmodeledNullGuard(ref, name)
}

/** Whether [e] runs only on some paths through its statement: in a safe call's selector, or in a branch (not the
 *  condition) of an `if` or `when`. */
private fun conditionallyRun(e: KtElement): Boolean {
    var node: KtElement? = e.parent
    var child: KtElement? = e
    while (node != null && node !is KtNamedFunction && node !is dev.ide.kotlin.syntax.psi.KtClassOrObject) {
        when (node) {
            is KtSafeQualifiedExpression -> if (child != null && child === node.selectorExpression) return true
            is KtIfExpression -> if (child != null && (child === node.then || child === node.`else`)) return true
            is dev.ide.kotlin.syntax.psi.KtWhenEntry -> if (child != null && child === node.expression) return true
            // A lambda or an object's body is what the backstop is for: leave it judged as it was.
            is KtLambdaExpression, is dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression -> return false
        }
        child = node
        node = node.parent
    }
    return false
}

/**
 * Whether parameter [index] of [callee] is declared, in this project's source, with a non-null type that
 * names no type parameter. Only then is "this parameter does not take null" a fact about the declaration: a
 * library signature reaches the model with its type arguments already substituted, and `Array<T?>.fill(T?)`
 * has by then become `fill(KtFile)`.
 */
internal fun KotlinSemanticChecks.plainNonNullSourceParameter(callee: KotlinSymbol, index: Int, nullableToo: Boolean = false): Boolean {
    val fn = callee.sourcePsi() as? KtNamedFunction ?: return false
    val text = fn.valueParameters.getOrNull(index)?.typeReference?.text?.trim() ?: return false
    if ((text.endsWith("?") && !nullableToo) || '(' in text) return false
    val typeParams = HashSet<String>()
    fn.typeParameters.forEach { tp -> tp.name?.let { typeParams += it } }
    var node: KtElement? = fn.parent
    while (node != null) {
        if (node is KtClassOrObject) node.typeParameters.forEach { tp -> tp.name?.let { typeParams += it } }
        node = node.parent
    }
    // `T & Any` is non-null whatever `T` is.
    if (DNN_TYPE_PARAM.matchEntire(text)?.groupValues?.get(1)?.let { it in typeParams } == true) return true
    return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text).none { it.value in typeParams }
}

private val DNN_TYPE_PARAM = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*&\s*Any""")

/**
 * Whether parameter [index] of the source function [callee] is a bare type parameter `T` (not `T?`) that [call]
 * fixes with a written non-null type argument: `checkSubtype<Int>(x)` takes no `null`, whatever `x` is.
 */
internal fun KotlinSemanticChecks.explicitNonNullTypeArgumentParameter(callee: KotlinSymbol, index: Int, call: KtCallExpression): Boolean {
    val fn = callee.sourcePsi() as? KtNamedFunction ?: return false
    val text = fn.valueParameters.getOrNull(index)?.typeReference?.text?.trim() ?: return false
    val written = call.typeArgumentList?.arguments ?: return false
    if (written.size != fn.typeParameters.size) return false
    val at = fn.typeParameters.indexOfFirst { it.name == text }
    if (at < 0) return false
    val arg = written[at].typeReference?.text?.trim() ?: return false
    return !arg.endsWith("?") && '(' !in arg && arg != "Nothing"
}

/**
 * The upper bound of the callee type parameter that [paramType] names, or null when it names none or the
 * parameter is unbounded. A same-file function's symbol carries its type-parameter NAMES only, so its bound
 * is read from the declaration.
 */
internal fun KotlinSemanticChecks.calleeTypeParameterBound(callee: KotlinSymbol, paramType: KotlinType, resolver: KotlinResolver): KotlinType? {
    val index = callee.typeParameters.indexOf(paramType.qualifiedName)
    if (index < 0 || paramType.typeArguments.isNotEmpty()) return null
    (callee.typeParameterBounds.getOrNull(index) as? KotlinType)?.let { return it }
    val fn = callee.sourcePsi() as? KtNamedFunction ?: return null
    val tp = fn.typeParameters.firstOrNull { it.name == paramType.qualifiedName } ?: return null
    if (fn.typeConstraints.any { it.subjectTypeParameterName?.getReferencedName() == tp.name }) return null // several bounds
    val text = tp.extendsBound?.text ?: return null
    return service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(fn))
}

/**
 * A bare call `f()` whose only targets are extensions, written where no receiver of theirs is in scope
 * (`fun String.shout()` called as `shout()` inside an unrelated class). Silent inside a lambda (which may
 * bring a receiver the model does not show) and whenever a receiver's supertypes are not known in full.
 */
internal fun KotlinSemanticChecks.extensionWithoutReceiver(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val parent = call.parent
    if (parent is KtQualifiedExpression && parent.selectorExpression === call) return null
    val name = callee.getReferencedName()
    val offset = callee.textRange.startOffset
    if (name.isEmpty() || resolver.localsAt(offset, name).isNotEmpty()) return null
    var node = call.parent
    while (node != null) {
        if (node is KtFunctionLiteral) return null
        node = node.parent
    }
    // The receivers the name's declarations ask for. Call resolution drops an extension it cannot bind, so
    // a same-file one is read from the file itself (which is also what makes the bare name "resolve").
    val expectedReceivers = ArrayList<KotlinType>()
    val sameFile = call.containingFile.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
    for (d in sameFile) {
        val fn = d as? KtNamedFunction ?: return null
        val recvText = fn.receiverTypeReference?.text ?: return null
        if (fn.typeParameters.any { it.name == recvText.substringBefore('<').trim().removeSuffix("?") }) return null
        expectedReceivers += service.typeFromText(recvText, resolver.fileContext) ?: return null
    }
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
    for (t in targets) {
        if (!t.isExtension || t.kind != SymbolKind.METHOD || t.receiverTypeParam != null) return null
        expectedReceivers += service.typeByFqn(t.receiverTypeFqn ?: return null)
    }
    if (expectedReceivers.isEmpty()) return null
    if (service.topLevelByName(name).any { it.receiverTypeFqn == null }) return null // a plain function of that name exists
    val receivers = resolver.implicitReceiversAt(offset)
    for (raw in expectedReceivers) {
        val expected = canonicalTypeForCheck(raw)
        if (expected.isTypeParameter || expected.qualifiedName == "kotlin.Any" || !service.isKnownType(expected.qualifiedName)) return null
        for (r in receivers) {
            if (r.isTypeParameter || !service.isKnownType(r.qualifiedName)) return null
            if (expected.isAssignableFrom(r) || canonicalName(r) == expected.qualifiedName) return null
            if (!(canonicalName(r) in FINAL_VALUE_TYPES || service.supertypeClosureKnown(r.qualifiedName))) return null
            // A member of the receiver with that name is what the call means.
            if (service.membersNamedForCheck(r.qualifiedName, r.typeArguments, name).any { it.name == name }) return null
        }
    }
    if (resolver.scopeMemberExtensions(offset, service.typeByFqn("kotlin.Any"), name).any { it.name == name }) return null
    val receiver = renderType(expectedReceivers.first())
    return error(
        callee, "Unresolved reference: $name (it is an extension on $receiver, and there is no $receiver receiver here)",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}

/**
 * A constructor call on a class declared in ANOTHER file of the project, whose arguments fit none of its
 * constructors. The binary check reads bytecode and the same-file one reads the tree in hand, so a project
 * class one file over was checked by neither: `Item("id", "t")` against `data class Item(val id: Long, …)`
 * passed. The source model records each constructor's parameter types, defaults and vararg, which is all the
 * same-file check uses.
 *
 * Backs off where a call of that name may not be a constructor call at all (a companion, which may define
 * `invoke`; a function of the same name), and on a vararg or a named or spread argument.
 */
internal fun KotlinSemanticChecks.projectConstructorMismatch(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
    val parent = call.parent
    val name = if (parent is KtQualifiedExpression && parent.selectorExpression === call) {
        parent.receiverExpression.text + "." + callee.getReferencedName()
    } else callee.getReferencedName()
    val fqn = resolver.constructorTypeFqn(name, callee.textRange.startOffset) ?: return null
    val cls = service.sourceClass(fqn) ?: return null
    if (cls.isObject || cls.companionObjectName != null || cls.constructors.isEmpty()) return null
    if (service.isNonInstantiableType(fqn) == true) return null
    val ctors = cls.constructors
    if (ctors.any { it.varargParamIndex >= 0 || it.paramHasDefault.size != it.paramTexts.size }) return null
    if (runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).any { it.kind == SymbolKind.METHOD }) return null
    val n = call.valueArguments.size
    fun arities(c: dev.ide.lang.kotlin.symbols.RawCallable) = c.paramHasDefault.count { !it }..c.paramTexts.size
    val fitting = ctors.filter { n in arities(it) }
    // A default-imported class of that name that can take the arguments is what the call resolves to instead.
    if (fitting.isEmpty() && name == callee.getReferencedName() && defaultImportedConstructorTakes(name, fqn, n)) return null
    if (fitting.isEmpty()) {
        val expected = ctors.flatMap { arities(it) }.distinct().sorted().joinToString("/")
        return error(
            call.valueArgumentList ?: callee, "No constructor of '${callee.getReferencedName()}' takes $n argument(s) (expected $expected)",
            KotlinDiagnosticCodes.CONSTRUCTOR_ARGS,
        )
    }
    val match = fitting.singleOrNull() ?: return null
    for ((i, arg) in call.valueArguments.withIndex()) {
        if (arg is KtLambdaArgument) continue
        val expr = arg.getArgumentExpression() ?: continue
        val pt = service.typeFromText(match.paramTexts.getOrNull(i)?.second, match.ctx, fqn) ?: continue
        val at = resolver.inferType(expr) ?: continue
        if (isMismatch(pt, at)) return mismatchDiagnostic(expr.textRange.startOffset, expr.textRange.endOffset, at, pt)
    }
    return null
}
