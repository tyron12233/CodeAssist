package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
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
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpressionBase
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.implicitReceiverMember
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.isTypeReceiver
import dev.ide.lang.kotlin.resolve.iterationElementType
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.pathGuardedNonNullAt
import dev.ide.lang.kotlin.resolve.receiverForMembers
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.resolve.smartCastNonNull
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
    val actual = resolver.inferType(e)
    // A branch of a choice is judged on its own only when its type is one to rely on: a literal, or a local
    // whose type was just read off its declaration. The whole expression (depth 0) keeps the rule it had.
    val trusted = depth == 0 || plainlyTyped(e)
    if (trusted && (isMismatch(declared, actual) || (strictNumeric && numericMismatch(declared, actual, e)))) {
        return mismatchDiagnostic(e.textRange.startOffset, e.textRange.endOffset, actual!!, declared)
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

/** Whether [value]'s nullable type can be trusted: no guard this model cannot follow stands in front of it. */
internal fun KotlinSemanticChecks.reliablyNullable(value: KtExpression, resolver: KotlinResolver): Boolean {
    return when (val e = unwrapParen(value)) {
        is KtNameReferenceExpression -> firstUseOfNullableLocal(e, resolver)
        is KtSafeQualifiedExpression -> {
            val receiver = e.receiverExpression
            resolver.inferType(receiver)?.nullable == true && reliablyNullable(receiver, resolver)
        }
        is KtBinaryExpressionWithTypeRHS -> e.operationReference.text.trim() == "as?"
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
internal fun KotlinSemanticChecks.numericMismatch(declared: KotlinType?, actual: KotlinType?, value: KtExpression): Boolean {
    if (declared == null || actual == null) return false
    val d = canonicalName(declared)
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

/** `"a" - 1`, `true * 2`: an arithmetic operator on a built-in value type that does not define it. */
internal fun KotlinSemanticChecks.undefinedBinaryOperator(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val name = ARITHMETIC_NAMES[e.operationToken] ?: return null
    val left = e.left ?: return null
    e.right ?: return null
    val lt = resolver.inferType(left) ?: return null
    if (lt.isTypeParameter || lt.nullable || canonicalName(lt) !in CLOSED_VALUE_TYPES) return null
    if (hasOperator(lt, name, left, resolver)) return null
    return error(
        e.operationReference, "Unresolved reference: $name (no such operator on ${renderType(lt)})",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}

/** `!5`, `-"a"`: a unary operator on a built-in value type that does not define it. */
internal fun KotlinSemanticChecks.undefinedUnaryOperator(e: KtPrefixExpression, resolver: KotlinResolver): Diagnostic? {
    val name = when (e.operationToken) {
        KtTokens.EXCL -> "not"; KtTokens.MINUS -> "unaryMinus"; KtTokens.PLUS -> "unaryPlus"
        else -> return null
    }
    val operand = e.baseExpression ?: return null
    val t = resolver.inferType(operand) ?: return null
    if (t.isTypeParameter || t.nullable) return null
    val tn = canonicalName(t)
    val closed = tn in CLOSED_VALUE_TYPES || (name == "not" && tn in NUMERIC)
    if (!closed || (name == "not" && tn == BOOLEAN)) return null
    if (hasOperator(t, name, operand, resolver)) return null
    return error(
        e.operationReference, "Unresolved reference: $name (no such operator on ${renderType(t)})",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
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
    val lambda = unwrapParen(init) as? KtLambdaExpression ?: return null
    val declared = service.typeFromText(declaredText, resolver.fileContext, enclosingClassFqnOf(init)) ?: return null
    if (declared.nullable) return null
    val arity = lambdaArity(declared) ?: return null
    val n = lambda.valueParameters.size
    if (n > 0 && n != arity) return lambdaParameterDiagnostic(lambda, arity)
    val result = declared.typeArguments.lastOrNull() as? KotlinType ?: return null
    if (result.isTypeParameter || canonicalName(result) == UNIT) return null // Unit coerces any last statement
    val last = lambda.bodyExpression?.statements?.lastOrNull()?.takeIf { it !is KtDeclaration } ?: return null
    if (last is KtReturnExpression) return null
    return valueMismatch(result, last, resolver)
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

/** The FQN of the outermost class enclosing [e], or null at the top level. */
private fun outermostClassFqn(e: KtElement): String? {
    var out: KtClassOrObject? = null
    var node: KtElement? = e
    while (node != null) {
        if (node is KtClassOrObject) out = node
        node = node.parent
    }
    return out?.fqName?.asString()
}

/** `a.p()` where `p` is private in a class this code is not written inside of. Members of one top-level
 *  class and everything nested in it see each other's privates closely enough that this leaves them alone. */
internal fun KotlinSemanticChecks.privateMemberAccess(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    val receiver = when {
        parent is KtQualifiedExpression && parent.selectorExpression === ref -> parent.receiverExpression
        parent is KtCallExpression && parent.calleeExpression === ref ->
            (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
        else -> null
    } ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val inferred = resolver.inferType(receiver) ?: return null
    val recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return null
    if (recvType.isTypeParameter || !service.isSourceClass(recvType.qualifiedName)) return null
    val members = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name).filter { it.name == name }
    if (members.isEmpty() || members.any { it.isExtension || Modifier.PRIVATE !in it.modifiers || !it.origin.fromSource }) return null
    if (resolver.scopeMemberExtensions(ref.textRange.startOffset, recvType, name).any { it.name == name }) return null
    fun ownerOf(m: KotlinSymbol): String? =
        m.declaringClassFqn ?: m.sourcePsi()?.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString()
    val owner = ownerOf(members.first()) ?: return null
    if (members.any { ownerOf(it) != owner }) return null
    val here = outermostClassFqn(ref)
    if (here != null && (owner == here || owner.startsWith("$here."))) return null
    return error(
        ref, "Cannot access '$name': it is private in '${owner.substringAfterLast('.')}'",
        KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
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
    if (e.operationToken != KtTokens.EQ) return null
    when (val left = e.left) {
        is KtDotQualifiedExpression -> {
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
            // `this.x = …` in a constructor or initializer initializes the property.
            if (unwrapParen(receiver).text == "this" && insideInitialization(e)) return null
            val members = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name).filter { it.name == name }
            if (members.isEmpty() || members.any { it.isExtension || it.kind != SymbolKind.FIELD || !isValDeclaration(it.sourcePsi()) }) return null
            if (resolver.scopeMemberExtensions(selector.textRange.startOffset, recvType, name).any { it.name == name }) return null
            return error(selector, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
        }
        is KtNameReferenceExpression -> {
            val name = left.getReferencedName()
            val offset = left.textRange.startOffset
            if (resolver.localsAt(offset, name).isNotEmpty() || insideInitialization(e)) return null
            if (e.getStrictParentOfType<KtNamedFunction>() == null) return null
            val member = resolver.implicitReceiverMember(name, offset) ?: return null
            if (member.kind != SymbolKind.FIELD || member.isExtension) return null
            val psi = member.sourcePsi() as? KtProperty ?: return null
            // A `val` with neither initializer nor getter is assigned exactly once somewhere this cannot see.
            if (psi.isVar || psi.isLocal || !(psi.hasInitializer() || psi.hasDelegate() || psi.getter != null)) return null
            return error(left, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
        }
        else -> return null
    }
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
            if (!plainNonNullSourceParameter(c, idx)) return null
            val pt = c.paramTypes.getOrNull(idx) as? KotlinType ?: return null
            if (pt.nullable || pt.isTypeParameter || !service.isKnownType(canonicalName(pt))) return null
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
 * `val a: Box = …` where `class Box<T>`: a generic source class named with no type arguments in a
 * declaration's type. An `is`/`as` target, a supertype, a callable reference and an annotation are all
 * places where the arguments may legitimately be absent or inferred, so only a property, parameter or
 * return type (and the arguments nested in one) is judged.
 */
internal fun KotlinSemanticChecks.missingTypeArguments(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
    if (userType.typeArgumentList != null || userType.qualifier != null || userType.parent is KtUserType) return null
    val name = userType.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return null
    // Climb out of the type; what holds it decides whether arguments are required.
    var node: KtElement = userType
    var topRef: KtTypeReference? = null
    while (true) {
        val p = node.parent ?: return null
        if (p is KtTypeReference) topRef = p
        if (p is KtDeclaration || p is KtExpression || p is KtSuperTypeEntry || p is KtSuperTypeCallEntry ||
            p is KtAnnotationEntry || p is KtFile
        ) { node = p; break }
        node = p
    }
    val holder = node
    val inDeclarationType = when (holder) {
        is KtTypeAlias -> false
        is KtProperty -> holder.typeReference === topRef
        is KtParameter -> holder.typeReference === topRef && holder.parent?.parent !is KtCatchClause
        is KtNamedFunction -> holder.typeReference === topRef
        else -> false
    }
    if (!inDeclarationType) return null
    if (service.isProjectTypeAlias(name) || resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return null
    val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(userType)) ?: return null
    if (!service.isSourceClass(fqn)) return null
    val expected = service.classTypeParameters(fqn).size
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

// --- overrides ----------------------------------------------------------------------------------------------

private fun mentionsTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsTypeParameter) != false }

/**
 * An `override` whose declared type no overridden member accepts (`override fun f(): String` over
 * `fun f(): Int`). Each candidate's type must be concrete: one that mentions a type parameter is spelled
 * differently in the subclass than in the supertype, and is not compared.
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
        if (!isMismatch(superType, declared)) return null
        expected = expected ?: superType
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
 * Whether parameter [index] of [callee] is declared, in this project's source, with a non-null type that
 * names no type parameter. Only then is "this parameter does not take null" a fact about the declaration: a
 * library signature reaches the model with its type arguments already substituted, and `Array<T?>.fill(T?)`
 * has by then become `fill(KtFile)`.
 */
internal fun KotlinSemanticChecks.plainNonNullSourceParameter(callee: KotlinSymbol, index: Int): Boolean {
    val fn = callee.sourcePsi() as? KtNamedFunction ?: return false
    val text = fn.valueParameters.getOrNull(index)?.typeReference?.text?.trim() ?: return false
    if (text.endsWith("?") || '(' in text) return false
    val typeParams = HashSet<String>()
    fn.typeParameters.forEach { tp -> tp.name?.let { typeParams += it } }
    var node: KtElement? = fn.parent
    while (node != null) {
        if (node is KtClassOrObject) node.typeParameters.forEach { tp -> tp.name?.let { typeParams += it } }
        node = node.parent
    }
    return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text).none { it.value in typeParams }
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
