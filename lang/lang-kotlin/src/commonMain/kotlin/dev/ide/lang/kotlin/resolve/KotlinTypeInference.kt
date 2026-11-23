package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef

/** Expression type inference: the declared-type-driven typer over literals, operators, casts, qualified access, and call/constructor results. */

private val ARITHMETIC_CONVENTIONS = mapOf(
    KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times",
    KtTokens.DIV to "div", KtTokens.PERC to "rem",
)

// Numeric-promotion ranks for arithmetic result inference (wider wins). Byte/Short/Char share the lowest
// rank (they promote to Int); Int/Long/Float/Double have distinct ranks so a result rank maps to one type.
internal val NUMERIC_RANK = mapOf(
    "kotlin.Double" to 5, "kotlin.Float" to 4, "kotlin.Long" to 3, "kotlin.Int" to 2,
    "kotlin.Short" to 1, "kotlin.Byte" to 1, "kotlin.Char" to 1,
)

fun KotlinResolver.inferType(expr: KtExpression?): KotlinType? {
    if (expr == null) return null
    if (KotlinResolverStats.enabled) KotlinResolverStats.inferCalls++
    // A narrowed type — or a type inferred under a pushed lambda-shape override (bidirectional overload
    // resolution) — is flow/context-dependent, not a property of the expression alone; bypass the cache so it
    // can't leak across narrowing scopes or poison a lambda body's real type with a candidate-scoped one.
    val cacheable = narrowings.isEmpty() && lambdaShapeOverrides.isEmpty()
    if (cacheable && inferCache.containsKey(expr)) return inferCache[expr]
    // During overload SCORING the ordinary [inferCache] is bypassed (a type inferred under a pushed lambda-shape
    // override is context-dependent). But `isApplicable` and `lambdaReturnSpecificity` re-type the SAME lambda
    // body, and every candidate re-types the SAME non-lambda arguments, so a large Compose file re-infers the
    // same expressions O(candidates) times per level — the allocation-heavy work behind the semantic pass's GC
    // storm. Memoize here too, dependency-tracked by exactly the OUTER overrides consulted (see
    // [KotlinResolver.scoringInferCache] / [resolveCalleeFunction]); skipped while narrowings are active.
    val scoringMemoable = scoringActive && narrowings.isEmpty()
    if (scoringMemoable) {
        scoringInferCache[expr]?.let { e ->
            if (overridesMatch(e.deps)) { propagateDeps(e.deps); return e.result }
        }
    }
    // Re-entrancy guard (see [KotlinResolver.inferringTypes]): a cycle that re-enters the SAME expression while
    // it is mid-inference can't be served from the cache (written only below), so break it with null.
    if (!inferringTypes.add(expr)) return null
    if (KotlinResolverStats.enabled) KotlinResolverStats.inferComputes++
    val frame = if (scoringMemoable) ScoringDepFrame().also { scoringDepFrames.addLast(it) } else null
    val r = try {
        approximateCapturedValue(when (expr) {
            is KtParenthesizedExpression -> inferType(expr.expression)
            // A label or an annotation on an expression does not change its value (`foo@ true` is a Boolean).
            is dev.ide.kotlin.syntax.psi.KtLabeledExpression -> inferType(expr.baseExpression)
            is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression -> inferType(expr.baseExpression)
            is KtConstantExpression -> constType(expr)
            is KtStringTemplateExpression -> service.typeByFqn("kotlin.String")
            is KtNameReferenceExpression -> companionValueType(expr) ?: typeOfName(
                expr.getReferencedName(),
                expr.textRange.startOffset
            )

            is KtCallExpression -> typeOfCall(expr, null)
            // `a?.b` is null whenever `a` is: on a nullable receiver its type is the selector's made nullable.
            is dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression -> typeOfQualified(expr)?.let { t ->
                if (!t.nullable && inferType(expr.receiverExpression)?.nullable == true) t.withNullable(true) else t
            }
            is KtQualifiedExpression -> typeOfQualified(expr)
            is KtClassLiteralExpression -> classLiteralType(expr)
            // An anonymous object (`object : Foo { }` / `object { }`): typed to the synthetic classifier the source
            // model registered its declaration under, so its own + supertype members enumerate + are checked.
            is KtObjectLiteralExpression -> objectLiteralType(expr)
            // An anonymous function (`fun(x: Int): Int = …`) used as a value → its `(P…) -> R` function type.
            is KtNamedFunction -> if (expr.name == null) anonymousFunctionType(expr) else null
            is KtThisExpression -> thisType(expr)
            is KtSuperExpression -> superType(expr)
            is KtBinaryExpression -> inferBinaryType(expr)
            is KtBinaryExpressionWithTypeRHS -> castType(expr)
            is KtPrefixExpression -> inferPrefixType(expr)
            is KtPostfixExpression -> inferPostfixType(expr)
            is KtArrayAccessExpression -> inferArrayGet(expr)
            // `if`/`when` used as an expression: the value is one of the branches, so its type is the common
            // type of the branches — made nullable if any branch is `null` (`fun f(): E? = when(x){ a->E.A;
            // else->null }`). (A common Compose pattern: `Icon(if (selected) IconA else IconB, …)` — typing the
            // arg disambiguate the overload.)
            is KtIfExpression -> inferBranchUnion(listOf(expr.then, expr.`else`))
            is KtWhenExpression -> inferBranchUnion(expr.entries.map { it.expression })
            // `try` as an expression is the same kind of choice: its block or one of its `catch` bodies.
            is KtTryExpression -> inferBranchUnion(listOf<KtExpression?>(expr.tryBlock) + expr.catchClauses.map { it.catchBody })
            // A reference to a PROPERTY (`::name`, `this::name`, `User::name`) is a `KProperty`, which is what
            // gives `::name.isInitialized` and `User::name.name` their members.
            is KtCallableReferenceExpression -> propertyReferenceType(expr) ?: functionReferenceType(expr)
            is dev.ide.kotlin.syntax.psi.KtLambdaExpression -> lambdaValueType(expr)
            // A jump never produces a value: it is `Nothing`, which joins away in `if (c) return x else y`.
            is dev.ide.kotlin.syntax.psi.KtReturnExpression, is dev.ide.kotlin.syntax.psi.KtThrowExpression,
            is dev.ide.kotlin.syntax.psi.KtBreakExpression, is dev.ide.kotlin.syntax.psi.KtContinueExpression ->
                service.typeByFqn("kotlin.Nothing")
            else -> null
        })
    } finally {
        if (frame != null) scoringDepFrames.removeLast()
        inferringTypes.remove(expr)
    }
    if (cacheable) inferCache[expr] = r
    else if (frame != null) scoringInferCache[expr] = ScoringEntry(r, frame.deps)
    return r
}

/**
 * A lambda used as a VALUE, not as a call argument (`val l = { 1 }`, `fun f() = { class C; C() }`): its declared
 * functional type when its context writes one, else the type K2 gives it from itself, `(P…) -> R` with `R` its
 * last expression's type. Null for a call argument (its slot types it, through [expectedLambdaShape]) and for
 * one whose parameters carry no types and that nothing types from outside.
 */
private fun KotlinResolver.lambdaValueType(lambda: dev.ide.kotlin.syntax.psi.KtLambdaExpression): KotlinType? {
    if (enclosingCallAndParamIndex(lambda) != null) return null
    var p = lambda.parent
    while (p is KtParenthesizedExpression || p is dev.ide.kotlin.syntax.psi.KtLabeledExpression || p is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression) p = p.parent
    if (p is KtBinaryExpression || p is KtQualifiedExpression || p is KtCallExpression) return null
    // A branch of an `if`/`when`/`try` value is typed with the other branches (`when { f != null -> f; else ->
    // { this + "K" } }` makes the lambda a `String.() -> String`), not from itself.
    if (isBranchValue(lambda)) return null
    contextualFunctionType(lambda)?.let { return it }
    if (lambda.valueParameters.any { it.typeReference == null }) return null
    val shape = lambdaFunctionType(lambda)
    if (lambda.valueParameters.isEmpty()) return shape
    // Every parameter carries its type, so the body can be typed for the result (`{ s: String -> s.length }`).
    val result = inferLambdaResult(lambda) as? KotlinType ?: return shape
    return service.typeByFqn(shape.qualifiedName, shape.typeArguments.dropLast(1) + result)
}

/** Whether [e] is the value of a branch of an `if`, `when` or `try` expression (its last statement, possibly
 *  inside a block). */
private fun isBranchValue(e: KtExpression): Boolean {
    var cur: dev.ide.kotlin.syntax.psi.KtElement = e
    var p = e.parent
    while (true) {
        when (p) {
            is KtParenthesizedExpression, is dev.ide.kotlin.syntax.psi.KtLabeledExpression, is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression -> {}
            is dev.ide.kotlin.syntax.psi.KtBlockExpression -> if (p.statements.lastOrNull() !== cur) return false
            is dev.ide.kotlin.syntax.psi.KtWhenEntry -> return true
            is KtIfExpression -> return p.condition?.textRange?.contains(e.textRange.startOffset) != true
            is dev.ide.kotlin.syntax.psi.KtTryExpression, is dev.ide.kotlin.syntax.psi.KtCatchClause -> return true
            is dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody -> {}
            else -> return false
        }
        cur = p as? dev.ide.kotlin.syntax.psi.KtElement ?: return false
        p = p.parent
    }
}

/**
 * The type a value has when the type substituted into its position was a PROJECTED type argument: `x.get()` on
 * an `Inv<out Number>` reads a captured `out Number`, a type the value only knows by its upper bound. K2
 * approximates such a captured type of a value to that bound (`Number`); for an `in` projection the bound is
 * `Any?` (`MutableList<in Int>.get()` is `Any?`). A star keeps its existing shape, read by the callers that
 * know it. Substitution leaves the projection on a top-level type parameter's binding, so the expression's type
 * is the one place the approximation happens.
 */
internal fun KotlinResolver.approximateCapturedValue(t: KotlinType?): KotlinType? = when (t?.projection) {
    null, "", "*" -> t
    "out" -> t.withProjection("")
    "in" -> service.typeByFqn("kotlin.Any").withNullable(true)
    else -> t
}

/** Infer the result type of a binary operator: comparison/equality/logical → `Boolean`; elvis → the
 *  non-null left (or the right); arithmetic → numeric promotion for the primitive number types (the builtins
 *  model carries no return type for `Int.times`/etc.), else the operator member's return type, with string
 *  `+` short-circuited to `String` (so `"x" + y` types even when `String.plus` isn't in the member index). */
internal fun KotlinResolver.inferBinaryType(e: KtBinaryExpression): KotlinType? =
    when (val token = e.operationToken) {
        KtTokens.ANDAND, KtTokens.OROR, KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
        KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> service.typeByFqn("kotlin.Boolean")

        KtTokens.ELVIS -> inferType(e.left)?.withNullable(false) ?: inferType(e.right)
        // `a in b` / `a !in b` desugar to a `contains` operator returning Boolean.
        KtTokens.IN_KEYWORD, KtTokens.NOT_IN -> service.typeByFqn("kotlin.Boolean")
        // An infix call (`a to b`, `0 until n`, a custom `infix fun`): the result is the function's return type,
        // so a chain off it (`(1 to 2).first`) can resolve its receiver. The `..`/`..<` range operators desugar
        // the same way (`rangeTo`/`rangeUntil`), so `(1..10).first` resolves too.
        KtTokens.IDENTIFIER -> binaryConventionReturn(e, e.operationReference.getReferencedName())
        KtTokens.RANGE -> binaryConventionReturn(e, "rangeTo")
        KtTokens.RANGE_UNTIL -> binaryConventionReturn(e, "rangeUntil")
        else -> {
            val convention = ARITHMETIC_CONVENTIONS[token] ?: return null
            val leftType = inferType(e.left) ?: return null
            val rightType = inferType(e.right)
            when {
                // `String.plus(Any?)`, and on a nullable left the stdlib's `String?.plus(Any?)`: a `String` either way.
                token == KtTokens.PLUS && leftType.qualifiedName == "kotlin.String" -> leftType.withNullable(false)
                // `Char` is not a number: `'a' + 1` and `c - 1` are `Char` (`Char.plus/minus(Int)`), `c - 'a'` is
                // an `Int` (`Char.minus(Char)`). Promotion ranked it with Byte/Short and typed all three `Int`.
                leftType.qualifiedName == "kotlin.Char" && rightType != null &&
                    (token == KtTokens.PLUS || token == KtTokens.MINUS) -> when (rightType.qualifiedName) {
                    "kotlin.Int" -> service.typeByFqn("kotlin.Char")
                    "kotlin.Char" -> if (token == KtTokens.MINUS) service.typeByFqn("kotlin.Int") else null
                    // Anything else is an extension operator's (`Char.plus(String): String`).
                    else -> arithmeticOperatorReturn(leftType, convention, rightType)
                }
                // Two primitive numbers → Kotlin's WIDENING promotion directly (`1 * 1.0` = Double, `1 + 1L` =
                // Long; Byte/Short/Char promote to Int). NOT [arithmeticOperatorReturn]: its by-argument overload
                // pick, over the built-in `times`/`plus` set whose numeric parameters accept each other loosely,
                // wrongly lands on `Int.times(Int): Int` for a `Double` argument — typing `1 * 1.0` as Int.
                leftType.qualifiedName in NUMERIC_RANK && rightType != null && rightType.qualifiedName in NUMERIC_RANK ->
                    numericResultType(leftType, rightType)
                // A custom `operator` whose parameter accepts the RIGHT operand wins over numeric promotion:
                // `2.dp * 2f` is `Dp.times(Float): Dp`, and `2f * 4.dp` is `Float.times(Dp): Dp` — NOT Float×Float.
                // Kotlin resolves the operator by the argument type; the primitive×primitive case is handled
                // above, so this covers a value-class / non-numeric operand, falling back to promotion when the
                // custom operator's return type is unknown.
                // A LOCAL `operator` extension is nearer than a top-level one (members still come first).
                else -> (if (service.membersNamed(leftType.qualifiedName, leftType.typeArguments, convention).none { it.kind == SymbolKind.METHOD && !it.isExtension })
                        localExtensionFunction(e.textRange.startOffset, leftType, convention, KtTokens.OPERATOR_KEYWORD)?.let { fn ->
                            fn.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(fn)) } ?: inferredExpressionBodyType(fn)
                        } else null)
                    ?: arithmeticOperatorReturn(leftType, convention, rightType)
                    ?: numericResultType(leftType, rightType)
            }
        }
    }

/**
 * The declared return type of the arithmetic `operator` [convention] on [leftType] whose single parameter
 * accepts the [rightType] argument — a CUSTOM operator or extension (`Dp.times(Float): Dp`, the top-level
 * `Float.times(Dp): Dp`), resolved by the argument type as the compiler does. Null when no matching overload
 * carries a return type — the primitive built-ins (`Int.plus(Int)`, …) do NOT (the model omits it), so those
 * fall through to [numericResultType]'s synthesized promotion. When [rightType] is unknown, the first single-
 * arg overload's type is used (a non-primitive left like `Dp` still yields its result; a primitive left's
 * built-in has no return type, so promotion still wins).
 */
internal fun KotlinResolver.arithmeticOperatorReturn(
    leftType: KotlinType,
    convention: String,
    rightType: KotlinType?,
): KotlinType? {
    val candidates =
        service.membersNamed(leftType.qualifiedName, leftType.typeArguments, convention)
            .filter { it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
    if (candidates.isEmpty()) return null
    val applicable = if (rightType == null) candidates
    else candidates.filter { c ->
        (c.paramTypes.first() as? KotlinType)?.let {
            paramAcceptsArg(
                it,
                rightType
            )
        } == true
    }
    val sym = mostSpecificByReceiver(applicable, leftType) ?: return null
    val ret = sym.type as? KotlinType ?: return null
    // A generic operator's own type parameters bind from the right operand (`operator fun <T> P.plus(other:
    // Parser<T>): Transform<T>` given a `Parser<String>` is a `Transform<String>`). An unbound one stays as is.
    if (sym.typeParameters.isEmpty() || rightType == null) return ret
    val param = sym.paramTypes.first() as? KotlinType ?: return ret
    val bindings = HashMap<String, TypeRef>()
    unify(param, rightType, bindings)
    bindings.keys.retainAll(sym.typeParameters.toSet())
    return if (bindings.isEmpty()) ret else service.substitute(ret, bindings) as? KotlinType ?: ret
}

/**
 * The most specific of several applicable operator overloads, by their EXTENSION RECEIVER.
 *
 * The stdlib declares `minus`/`plus` on `Set<T>` AND on `Iterable<T>`, and both accept the same argument.
 * Taking the first match made `setA - setB` a `List<T>`, so `(this - other) + (other - this)` stopped being a
 * `Set` -- the shape a symmetric difference is written to preserve. Kotlin picks the most specific applicable
 * overload, which is what this ranks: a MEMBER beats every extension, an extension on the left type itself
 * beats one on a supertype, and otherwise the receiver that is narrower than the most alternatives wins.
 */
private fun KotlinResolver.mostSpecificByReceiver(
    candidates: List<KotlinSymbol>,
    leftType: KotlinType,
): KotlinSymbol? {
    if (candidates.size <= 1) return candidates.firstOrNull()
    return candidates.maxByOrNull { c ->
        val recv = c.receiverTypeFqn ?: return@maxByOrNull Int.MAX_VALUE // a member, not an extension
        if (recv == leftType.qualifiedName) return@maxByOrNull Int.MAX_VALUE - 1
        candidates.count { o ->
            val other = o.receiverTypeFqn
            o !== c && other != null && other != recv &&
                service.typeByFqn(other).isAssignableFrom(service.typeByFqn(recv))
        }
    }
}

/** Canonicalize a JVM scalar/value type to its Kotlin classifier before comparing two types: a type read from
 *  bytecode keeps its JVM name (`java.lang.String`) and IS the Kotlin type it maps to, so without this a
 *  `java.lang.String` parameter looks unrelated to a `kotlin.String` argument. The flexible platform COLLECTION
 *  types are left as their JVM name on purpose (a Java `List` is assignable to both `List` and `MutableList`),
 *  so a caller sees an unmodeled name there and backs off rather than concluding a clash. */
internal fun canonicalTypeForCheck(t: KotlinType): KotlinType {
    // A Kotlin TYPEALIAS and its JVM target are the same class under two spellings, so collapse to one of
    // them before comparing. See [Builtins.TYPEALIAS_TO_JVM].
    Builtins.TYPEALIAS_TO_JVM[t.qualifiedName]?.let { return t.withClassifier(it) }
    val mapped = Builtins.kotlinTypeFor(t.qualifiedName) ?: return t
    return if (mapped.startsWith("kotlin.collections")) t else t.withClassifier(mapped)
}

/** Whether a parameter of type [param] accepts an argument of type [arg] — an assignable/same type, a generic
 *  parameter (binds to anything), or a numeric-to-numeric adaptation (`times(Int)` called with a `Long`). */
internal fun KotlinResolver.paramAcceptsArg(param: KotlinType, arg: KotlinType): Boolean {
    if (param.isTypeParameter) return true
    if (param.isAssignableFrom(arg)) return true
    return param.qualifiedName in NUMERIC_RANK && arg.qualifiedName in NUMERIC_RANK
}

/** The return type of a single-argument binary CONVENTION call `left <name> right` — a custom `infix fun`,
 *  `to`/`until`, or a `..`/`..<` desugaring to `rangeTo`/`rangeUntil`. Resolved as the single-param member of
 *  the left type (member-first), else a single-param extension on it, with the callable's own type parameters
 *  bound from the right operand (`"" to 1` → `Pair<String, Int>`). Null when unresolved. */
internal fun KotlinResolver.binaryConventionReturn(
    e: KtBinaryExpression,
    name: String
): KotlinType? {
    val leftType = inferType(e.left) ?: return null
    val member = service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
    val exts = service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name, exactName = true)
        .filter { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
    // Prefer the extension declared on the EXACT left type over one on a supertype / type-parameter bound —
    // Kotlin's most-specific-receiver overload resolution. `10f..3f` sees BOTH the generic
    // `Comparable<T>.rangeTo(T): ClosedRange<T>` and the specific `Float.rangeTo(Float):
    // ClosedFloatingPointRange<Float>` (float ranges are floating-point ranges, defined as an extension in
    // stdlib `Ranges.kt`, not a class member like `Int.rangeTo`). The specific one must win, else the range
    // is mis-typed as its `ClosedRange` supertype and `val r: ClosedFloatingPointRange<Float> = 10f..3f` is
    // false-flagged a type mismatch. Without the tiebreak the first match (the generic one) wins arbitrarily.
    // Only extensions in scope count, and among equally specific ones the file's own package (a project source
    // declaration) outranks the default imports: `infix fun <A, B> A.to(that: B)` beside a project `Pair` is
    // the `to` there, not `kotlin.to`.
    // A LOCAL `operator` extension is nearer than any top-level or imported one.
    if (member == null) localExtensionFunction(e.textRange.startOffset, leftType, name, KtTokens.OPERATOR_KEYWORD)?.let { fn ->
        return fn.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(fn)) } ?: inferredExpressionBodyType(fn)
    }
    val scoped = exts.filter { extensionInScope(it) }.ifEmpty { exts }
    val callable = member
        ?: scoped.firstOrNull { it.receiverTypeFqn == leftType.qualifiedName && it.origin.fromSource }
        ?: scoped.firstOrNull { it.receiverTypeFqn == leftType.qualifiedName }
        ?: scoped.firstOrNull { it.origin.fromSource }
        ?: scoped.firstOrNull()
    val raw = callable?.type as? KotlinType
    if (raw == null || callable.typeParameters.isEmpty()) return raw
    val bindings = HashMap<String, TypeRef>()
    (callable.paramTypes.firstOrNull() as? KotlinType)?.let { pt ->
        inferType(e.right)?.let {
            unify(
                pt,
                it,
                bindings
            )
        }
    }
    return service.substitute(raw, bindings) as? KotlinType ?: raw
}

/** A unary prefix expression's type: `!x` → its `not()` (else Boolean); `-x`/`+x`/`++x`/`--x` → the operand's
 *  own type for a primitive number, else the `unaryMinus`/`unaryPlus`/`inc`/`dec` operator's return type. So
 *  `(-vec).member` and `(!flag)` resolve for custom and built-in operators alike. */
internal fun KotlinResolver.inferPrefixType(e: KtPrefixExpression): KotlinType? {
    val operand = inferType(e.baseExpression)
    if (e.operationToken == KtTokens.EXCL) {
        (operand?.takeIf { !it.isTypeParameter })?.let { op ->
            (unaryOperator(op, "not"))?.let { return it }
        }
        return service.typeByFqn("kotlin.Boolean")
    }
    if (operand == null || operand.isTypeParameter) return null
    if (operand.qualifiedName in NUMERIC_RANK) return operand // primitive +/-/++/-- yields the operand's type
    val name = when (e.operationToken) {
        KtTokens.MINUS -> "unaryMinus"; KtTokens.PLUS -> "unaryPlus"
        KtTokens.PLUSPLUS -> "inc"; KtTokens.MINUSMINUS -> "dec"
        else -> return null
    }
    return unaryOperator(operand, name)
}

/** A postfix expression's type: `x!!` (the not-null assertion) → the operand's type made non-null, so a
 *  member chain off it (`nullable!!.member`, `map[k]!!.foo()`) resolves; `x++`/`x--` yield the operand's own
 *  type. Null when the operand can't be typed. */
internal fun KotlinResolver.inferPostfixType(e: KtPostfixExpression): KotlinType? {
    // `null!!` always throws: it is `Nothing`.
    if (e.operationToken == KtTokens.EXCLEXCL && e.baseExpression?.let { unwrapParens(it) }?.let { it is KtConstantExpression && it.text == "null" } == true)
        return service.typeByFqn("kotlin.Nothing")
    val operand = inferType(e.baseExpression) ?: return null
    if (e.operationToken != KtTokens.EXCLEXCL) return operand
    // `t!!` on a `t: T` is `T & Any`, which stays non-null whatever `T` is later bound to. A value typed by its
    // declaration's text may name the type parameter without carrying the flag.
    val tp = if (!operand.isTypeParameter && operand.typeArguments.isEmpty() && '.' !in operand.qualifiedName &&
        isTypeParameterInScope(operand.qualifiedName, e.textRange.startOffset)
    ) KotlinType(operand.qualifiedName, emptyList(), operand.nullable, service, isTypeParameter = true) else operand
    return tp.definitelyNotNull()
}

/** The return type of a zero-argument unary operator [name] on [type] (member or extension). */
internal fun KotlinResolver.unaryOperator(type: KotlinType, name: String): KotlinType? =
    service.membersNamed(type.qualifiedName, type.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.isEmpty() }?.type as? KotlinType

/** `value as T` / `value as? T` → the target type `T`, nullable for a safe cast (`as?` yields `T?`) or a
 *  written nullable target (`as T?`). Lets `(x as T).member` resolve members against `T`. */
internal fun KotlinResolver.castType(e: KtBinaryExpressionWithTypeRHS): KotlinType? {
    val raw = e.right?.text?.trim() ?: return null
    val safe = e.operationReference.getReferencedNameElementType() == KtTokens.AS_SAFE
    val nullable = safe || raw.endsWith("?")
    val t = service.typeFromText(raw.removeSuffix("?"), fileContext, enclosingClassFqnOf(e)) ?: return null
    return if (nullable) t.withNullable(true) else t
}

/** Kotlin numeric-promotion result type for arithmetic between primitive numbers: the wider operand, with
 *  Byte/Short/Char promoted to `Int`. Null when [left] isn't a primitive number (so a non-numeric operator
 *  overload falls back to its declared return type). An unknown [right] doesn't pull the result below [left]. */
internal fun KotlinResolver.numericResultType(left: KotlinType, right: KotlinType?): KotlinType? {
    val lr = NUMERIC_RANK[left.qualifiedName] ?: return null
    // A KNOWN non-numeric right operand isn't primitive arithmetic — its operator (if any) was already tried by
    // [arithmeticOperatorReturn]; don't force a numeric result here (that made `2f * 4.dp` read as Float, not Dp).
    if (right != null && !right.isTypeParameter && right.qualifiedName !in NUMERIC_RANK &&
        service.isKnownType(right.qualifiedName)
    ) return null
    val rr = right?.qualifiedName?.let { NUMERIC_RANK[it] } ?: -1
    val rank = maxOf(lr, rr).coerceAtLeast(NUMERIC_RANK.getValue("kotlin.Int"))
    return service.typeByFqn(NUMERIC_RANK.entries.first { it.value == rank }.key)
}

/** The value of an `if`/`when` branch: a bare expression as-is, or the last statement of a `{ … }` block
 *  branch (so `if (c) { x } else { y }` still types). Null when the branch is absent or not an expression. */
/**
 * K2 resolves a constructor call level by level and takes the first level with an APPLICABLE candidate: when the
 * class [ownFqn] that [name] reaches first has no constructor for [call]'s argument count, the default-imported
 * `kotlin.[name]` that does is the one called (`class Array<E>(e: E)` beside `Array(1) { … }`, kt15161). Null
 * when the own class fits or no default-imported class of that name does.
 */
internal fun KotlinResolver.defaultImportedConstructorOver(name: String, ownFqn: String, call: KtCallExpression): String? {
    val fallback = dev.ide.lang.kotlin.symbols.Builtins.DEFAULT_SIMPLE_TYPES[name]?.takeIf { it != ownFqn } ?: return null
    val n = call.valueArguments.size
    val own = service.sourceClass(ownFqn)?.constructors
    val ownFits = if (own != null) own.isEmpty() && n == 0 || own.any { c -> c.varargParamIndex >= 0 || n in c.paramHasDefault.count { !it }..c.paramTexts.size }
        else service.constructorsOf(ownFqn).let { cs -> cs.isEmpty() || cs.any { it.varargParamIndex >= 0 || it.paramTypes.size == n } }
    if (ownFits) return null
    val ctors = service.constructorsOf(fallback).ifEmpty {
        service.builtinShapeFor(fallback)?.members?.filter { it.kind == SymbolKind.CONSTRUCTOR }.orEmpty()
    }
    return fallback.takeIf { ctors.any { it.paramTypes.size == n } }
}

internal fun KotlinResolver.branchExpr(branch: KtExpression?): KtExpression? = when (branch) {
    is KtBlockExpression -> branch.statements.lastOrNull()
    else -> branch
}

/** The type of an `if`/`when` used as an expression: the common classifier of its branches, made nullable
 *  if any branch is `null` (or itself nullable). Conservative — if the typeable branches disagree on
 *  classifier, or any branch's type is unknown, returns null so the type-mismatch check backs off rather
 *  than guess a least-upper-bound (which could false-positive against a more specific declared type). */
internal fun KotlinResolver.inferBranchUnion(branches: List<KtExpression?>): KotlinType? {
    var common: KotlinType? = null
    var nullable = false
    var disagree = false
    var literal = false
    val types = ArrayList<KotlinType>(branches.size)
    // A lambda branch takes its type from the others when one of them is a function (`f != null -> f; else -> { … }`),
    // and is typed by itself when none is (`if (c) ({ x }) else { 2 }` is a `() -> Int`).
    val lambdaOwnType = branches.none { b ->
        val e = branchExpr(b)?.let { unwrapParens(it) } ?: return@none false
        e !is dev.ide.kotlin.syntax.psi.KtLambdaExpression && !isNullBranch(e) && inferType(e)?.let { dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(it.qualifiedName) } == true
    }
    for (b in branches) {
        val e = branchExpr(b) ?: continue
        if (isNullBranch(e)) {
            nullable = true; continue
        }
        val lambda = unwrapParens(e) as? dev.ide.kotlin.syntax.psi.KtLambdaExpression
        val t = (if (lambda != null && lambdaOwnType && lambda.valueParameters.all { it.typeReference != null })
            lambdaFunctionType(lambda).let { shape ->
                if (lambda.valueParameters.isEmpty()) shape
                else (inferLambdaResult(lambda) as? KotlinType)?.let { r -> service.typeByFqn(shape.qualifiedName, shape.typeArguments.dropLast(1) + r) } ?: shape
            } else inferType(e)) ?: return null // an unknown branch → can't confidently unify
        // So is one typed by a class nothing declares, or by a type variable of some callee that inference left
        // open (`pending[j]` on an unresolved `ArrayList<E>`): joining it would make `Any` of what is not known.
        // (A function type is known by its shape: `kotlin.FunctionN` is synthetic, not declared.)
        if (if (t.isTypeParameter) !isTypeParameterInScope(t.qualifiedName, e.textRange.startOffset)
            else !service.isKnownType(canonicalTypeForCheck(t).qualifiedName) &&
                !dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(t.qualifiedName)) return null
        // A branch that never completes (`throw`, `return`, `TODO()`) adds nothing to the result.
        if (t.qualifiedName == "kotlin.Nothing" && !t.nullable) continue
        if (e is KtConstantExpression || (e is dev.ide.kotlin.syntax.psi.KtPrefixExpression && e.baseExpression is KtConstantExpression)) literal = true
        if (t.nullable) nullable = true
        types += t
        if (common == null) common = t
        else if (t.qualifiedName != common.qualifiedName) disagree = true
    }
    if (!disagree) return common?.withNullable(nullable)
    // Branches of different classes: K2 types the expression as their nearest common supertype (`if (c) None()
    // else r` with both `Option<T>`s is an `Option<T>`). A number literal is typed by what is expected of it, so a
    // branch of one leaves the join to the expected type instead.
    if (literal) return null
    val lub = commonSupertypesType(types.map { it.withNullable(false) }) ?: return service.typeByFqn("kotlin.Any").withNullable(nullable)
    // Its type arguments when every branch instantiates it alike.
    val args = types.map { t ->
        if (t.qualifiedName == lub.qualifiedName) t.typeArguments
        else service.receiverSupertypeArgs(t.qualifiedName, t.typeArguments, lub.qualifiedName) ?: return lub.withNullable(nullable)
    }.distinctBy { a -> a.joinToString { it.toString() } }
    val typed = if (args.size == 1 && args[0].isNotEmpty()) service.typeByFqn(lub.qualifiedName, args[0]).withIntersection(lub.intersection) else lub
    return typed.withNullable(nullable)
}

internal fun KotlinResolver.isNullBranch(e: KtExpression): Boolean =
    e is KtConstantExpression && e.text.trim() == "null"

/** `xs[i]` → the element type: the (substituted) return type of the receiver's `get(index)` operator
 *  (`List<String>.get` → `String`, `Map<K,V>.get` → `V?`). The receiver's type arguments come from its type;
 *  `get`'s OWN type parameters are inferred from the index expressions ([operatorResultType]), which is what
 *  types `coroutineContext[CoroutineName]` — `get(key: Key<E>): E?` — as `CoroutineName?`. Null when the
 *  receiver type or `get` is unknown. */
internal fun KotlinResolver.inferArrayGet(e: KtArrayAccessExpression): KotlinType? {
    val recv = inferType(e.arrayExpression) ?: return null
    val arity = e.indexExpressions.size
    val get = service.membersNamed(recv.qualifiedName, recv.typeArguments, "get")
        .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.size == arity } ?: return null
    return operatorResultType(get, e.indexExpressions)
}

/** `this` (optionally `this@Label`) → the matching implicit receiver in scope (innermost when unlabeled). */
internal fun KotlinResolver.thisType(expr: KtThisExpression): KotlinType? {
    val receivers = implicitReceiversAt(expr.textRange.startOffset)
    val label = expr.getLabelName()
    if (label == null) return receivers.firstOrNull()
    // A label names a function, lambda or class, not a type: `this@foo` in `fun Any?.foo()` is foo's receiver.
    // The innermost one is the receiver at the head of the chain, which carries any smart cast of `this`.
    labeledReceiverOwner(expr)?.let { (owner, innermost) ->
        if (innermost) receivers.firstOrNull()?.let { return it }
        val declared = when (owner) {
            is KtNamedFunction -> owner.receiverTypeReference?.text
                ?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(owner)) }
            is dev.ide.kotlin.syntax.psi.KtLambdaExpression -> lambdaReceiverType(owner)
            is dev.ide.kotlin.syntax.psi.KtClassOrObject -> owner.fqName?.asString()?.let { fqn ->
                service.typeByFqn(fqn, (owner as? dev.ide.kotlin.syntax.psi.KtClass)?.typeParameters.orEmpty().mapNotNull { tp ->
                    tp.name?.let { KotlinType(it, emptyList(), false, service, isTypeParameter = true) }
                })
            }
            else -> null
        }
        if (declared != null) return if (declared.nullable && outerReceiverNullChecked(expr, owner)) declared.withNullable(false) else declared
    }
    return receivers.firstOrNull { it.qualifiedName.substringAfterLast('.') == label }
}

/**
 * Whether an `if (this != null)` (or `this@label != null`) written in [owner]'s own body, outside every receiver
 * scope nested in it, encloses [expr] in its then-branch: the outer receiver `this@label` reads is then non-null.
 */
private fun KotlinResolver.outerReceiverNullChecked(expr: KtThisExpression, owner: KtElement): Boolean {
    var node: KtElement? = expr.parent as? KtElement
    // A check counts only when no receiver scope lies between it and [owner] (it is then about [owner]'s `this`).
    var checked = false
    while (node != null && node !== owner) {
        if (node is dev.ide.kotlin.syntax.psi.KtLambdaExpression || node is KtNamedFunction || node is dev.ide.kotlin.syntax.psi.KtClassOrObject) {
            checked = false
        } else if (node is KtIfExpression && node.then?.textRange?.contains(expr.textRange.startOffset) == true) {
            val c = unwrapParens(node.condition) as? KtBinaryExpression
            if (c != null && c.operationToken == KtTokens.EXCLEQ) {
                val l = unwrapParens(c.left)
                val r = unwrapParens(c.right)
                val thisSide = if (r is KtConstantExpression && r.text == "null") l else if (l is KtConstantExpression && l.text == "null") r else null
                if (thisSide is KtThisExpression) checked = true
            }
        }
        node = node.parent as? KtElement
    }
    return checked
}

/** `super` (optionally `super<Base>`) → the enclosing class's supertype, so `super.member` lists inherited
 *  members. With no explicit supertype, `kotlin.Any` (whose members `toString`/`hashCode`/… are inherited). */
internal fun KotlinResolver.superType(expr: KtSuperExpression): KotlinType? {
    expr.superTypeQualifier?.text?.let {
        service.typeFromText(it, fileContext)?.let { t -> return t }
    }
    // `super@Outer.bar()`: the supertypes of the class the label names, not of the innermost one.
    val label = expr.getLabelName()?.removeSurrounding("`")
    val cls = (if (label != null) generateSequence(expr.parent as? KtElement) { it.parent as? KtElement }
        .filterIsInstance<KtClassOrObject>().firstOrNull { it.name == label } else null)
        ?: enclosingClassOrObject(expr.textRange.startOffset) ?: return null
    // An enum entry with a body is a subclass of its enum class, which it never writes as a supertype:
    // `E1 { override fun foo() = super.outerFun() }` calls the enum's own `outerFun`.
    if (cls is KtEnumEntry) {
        (cls.parent?.parent as? KtClass)?.takeIf { it.isEnum() }?.let { enumCls ->
            enclosingClassFqnOf(enumCls)?.let { return service.typeByFqn(it) }
        }
    }
    val owner = enclosingClassFqnOf(cls)
    val supers = cls.superTypeListEntries.mapNotNull { e -> e.typeReference?.text?.let { service.typeFromText(it, fileContext, owner) } }
    // An unqualified `super.m()` reaches `m` in WHICHEVER supertype declares it, the interfaces included:
    // `class D : Base(), Iface { override fun bar() = super.bar() }` calls `Iface.bar`. Typing `super` as the
    // first supertype alone made every interface default reached that way an unresolved reference.
    // Only for a bare `super`: a label (`super@Outer`) names another class's supertypes, and an empty `super<>`
    // names none.
    if (supers.size > 1 && '<' !in expr.text && '@' !in expr.text) {
        val selector = (expr.parent as? KtQualifiedExpression)?.takeIf { it.receiverExpression === expr }?.selectorExpression
        val name = ((selector as? KtCallExpression)?.calleeExpression ?: selector)
            ?.let { (it as? KtNameReferenceExpression)?.getReferencedName() }
        if (name != null) supers.firstOrNull { memberNamed(it, name) != null }?.let { return it }
    }
    return supers.firstOrNull() ?: service.typeByFqn("kotlin.Any")
}

internal fun KotlinResolver.constType(e: KtConstantExpression): KotlinType? {
    val t = e.text.trim()
    val unsigned = unsignedSuffix(t)
    return service.typeByFqn(
        when {
            t == "true" || t == "false" -> "kotlin.Boolean"
            t == "null" -> return null
            t.startsWith("'") -> "kotlin.Char"
            // A radix-prefixed integer literal: its a-f / e digits are NOT a float exponent/suffix, and a
            // value that overflows Int widens to Long (so a 32-bit ARGB hex like `0xFFD32F2F` types as Long
            // → the `Color(Long)` overload, not `Color(Int)`/Double).
            t.startsWith("0x", true) || t.startsWith("0b", true) -> hexBinType(t)
            // The UNSIGNED suffix, and it has to be read before the plain `L` branch below, because `3uL` ends
            // with `L` too: reading only that made it a `Long`, and `val n: ULong = 3uL` was then a type
            // mismatch against the literal's own spelling. `3u` was no better -- it fell through to the decimal
            // branch, where `"3u".toLongOrNull()` is null, and defaulted to `Int`.
            unsigned != null -> unsigned
            t.endsWith("L") || t.endsWith("l") -> "kotlin.Long"
            t.endsWith("f") || t.endsWith("F") -> "kotlin.Float"
            '.' in t || 'e' in t || 'E' in t -> "kotlin.Double"
            else -> if ((t.replace("_", "").toLongOrNull()
                    ?: 0L) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) "kotlin.Int" else "kotlin.Long"
        },
    )
}

/** The type of a hex/binary integer literal — `Long` with an `L` suffix or when the value overflows `Int`,
 *  and the unsigned pair of those with a `u` suffix. */
internal fun KotlinResolver.hexBinType(raw: String): String {
    val radix = if (raw[1].lowercaseChar() == 'x') 16 else 2
    val (body, unsigned, isLong) = intSuffixes(raw.substring(2))
    val u = body.toULongOrNull(radix) ?: return if (unsigned) "kotlin.UInt" else "kotlin.Int"
    if (unsigned) {
        // An unsigned literal widens at UInt's ceiling, not at Int's: `0xFFFFFFFFu` is a `UInt`, and only
        // something past that is a `ULong`. Reusing the signed range here is how `0xFFu` typed as `Int`.
        return if (isLong || u > UInt.MAX_VALUE.toULong()) "kotlin.ULong" else "kotlin.UInt"
    }
    val v = u.toLong()
    return if (isLong || v !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "kotlin.Long" else "kotlin.Int"
}

/**
 * The type of a DECIMAL integer literal carrying the unsigned suffix, or null when it carries none.
 *
 * Kotlin narrows an unsigned literal to `UByte`/`UShort` from the expected type; this model does not carry an
 * expected type, so it stops at `UInt`/`ULong` — the same place a signed literal stops at `Int`/`Long`.
 */
private fun unsignedSuffix(raw: String): String? {
    val (body, unsigned, isLong) = intSuffixes(raw)
    if (!unsigned) return null
    val u = body.toULongOrNull() ?: return "kotlin.UInt"
    return if (isLong || u > UInt.MAX_VALUE.toULong()) "kotlin.ULong" else "kotlin.UInt"
}

/**
 * An integer literal split into its digits and its type suffixes: `(body, unsigned, long)`.
 *
 * `u`/`U` and `L` are read in either order. Kotlin only spells the combined form `1uL`/`1UL`, so the other
 * order cannot reach a well-formed file; accepting it costs nothing and means the reader does not have to
 * trust the lexer's ordering to see that this is right. Underscores are separators, not digits.
 */
private fun intSuffixes(raw: String): Triple<String, Boolean, Boolean> {
    var body = raw.replace("_", "")
    var unsigned = false
    var long = false
    while (body.isNotEmpty()) {
        when (body.last()) {
            'u', 'U' -> if (unsigned) return Triple(body, unsigned, long) else unsigned = true
            'L', 'l' -> if (long) return Triple(body, unsigned, long) else long = true
            else -> return Triple(body, unsigned, long)
        }
        body = body.dropLast(1)
    }
    return Triple(body, unsigned, long)
}

internal fun KotlinResolver.typeOfQualified(q: KtQualifiedExpression): KotlinType? {
    // A fully-qualified type used directly (`android.R`, `java.util.Locale`) — resolve the whole text.
    if (service.isKnownType(q.text)) return service.typeByFqn(q.text)
    // A member access path checked earlier (`if (b.x is String) b.x.length`, `if (Obj.y is Int) n = Obj.y`) is
    // smart cast like a variable. Off while the lowerer drives its own narrowing stack, as for names.
    if (narrowings.isEmpty() && q is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression && checkedPathSelectors().isNotEmpty() &&
        (q.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()?.let { it in checkedPathSelectors() } == true
    ) {
        qualifiedPathKey(q)?.takeIf { it in checkedPaths() }
            ?.let { key -> pathSmartCastAt(q, key, q.textRange.startOffset)?.let { return it } }
    }
    // Static ENUM-CONSTANT access (`SomeEnum.A`): the constant's value type IS the enum. Resolve it via the
    // receiver's TYPE denotation FIRST — so it works even when the receiver name also resolves to a value
    // (an enum `E` shadowed by the top-level `kotlin.math.E`, a Double), and without this `SomeEnum.A` is
    // mis-resolved as a nested classifier named `A`. Enum entries aren't instance members, so `membersNamed`
    // never surfaces them.
    (q.selectorExpression as? KtNameReferenceExpression)?.let { sel ->
        typeDenotationFqn(q.receiverExpression)?.let { fqn ->
            if (sel.getReferencedName() in enumConstantNames(fqn)) return service.typeByFqn(fqn)
        }
    }
    // A NESTED-class constructor call reached through its enclosing type — `FrameLayout.LayoutParams(w, h)`,
    // `Outer.Inner(…)`. The receiver denotes a TYPE (not a value) and `Owner.Nested` is a known type, so the
    // call CONSTRUCTS it (its type params inferred from the args). Without this the selector is mis-read as a
    // member function of `Owner` — none exists — so the whole expression (and any `.apply { }` receiver off
    // it, whose implicit `this` is this type) fails to type and bare members inside the block flag unresolved.
    (q.selectorExpression as? KtCallExpression)?.let { call ->
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (callee != null && callee.firstOrNull()?.isUpperCase() == true) {
            typeDenotationFqn(q.receiverExpression)?.let { ownerFqn ->
                "$ownerFqn.$callee".takeIf { service.isKnownType(it) }
                    ?.let { return constructorResultType(it, call) }
            }
        }
    }
    val inferred = inferType(q.receiverExpression)
        ?: typeOfTypeName(q.receiverExpression) // receiver may be a class name (companion/static access)
        ?: return fullyQualifiedConstructorCall(q)
    // A bare type-parameter receiver (`t.member` where `t: T`, `<T : Bound>`) resolves its members against the
    // parameter's upper bound; a normal type is unchanged, an unbounded parameter yields null (no members).
    val receiverType =
        receiverForMembers(inferred, q.receiverExpression.textRange.startOffset) ?: return null
    return when (val sel = q.selectorExpression) {
        is KtCallExpression -> typeOfCall(sel, receiverType)
        is KtNameReferenceExpression -> {
            val name = sel.getReferencedName()
            // A member property/field, else a NESTED type/object reached through its enclosing type
            // (`Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`) — a classifier, not a member, resolved by
            // probing the candidate nested FQN (so an icon extension-property receiver
            // `Icons.AutoMirrored.Filled.List` types and the property binds as an extension) — else, for a
            // `Type.member` (not an instance) access, a COMPANION-object member (`MyClass.CONST`), which the
            // instance member lookup never surfaces (the companion is a distinct classifier).
            // A CLASS named as the receiver reads its static scope, where the companion's member is found and the
            // class's own instance member is not (`Query.inProgress` is the companion's `Query<Nothing>`, not the
            // instance `inProgress: Boolean`, kt32106).
            val typeReceiver = isTypeReceiver(q.receiverExpression)
            if (typeReceiver) companionMemberType(receiverType.qualifiedName, name)?.let { return it }
            memberNamed(receiverType, name)?.type as? KotlinType
                ?: nestedType(receiverType.qualifiedName, name)
                // A member extension PROPERTY of an enclosing class or an implicit receiver (`1.u` with
                // `val <T> T.u` declared in `A`, inside `with(a)`), its receiver parameter bound from `1`.
                ?: (if (!typeReceiver && mayBeMemberExtensionProperty(name)) scopeMemberExtensions(sel.textRange.startOffset, receiverType, name, exactName = true)
                    .firstOrNull { it.name == name && it.kind == SymbolKind.FIELD }?.type as? KotlinType else null)
                ?: if (typeReceiver) companionMemberType(
                    receiverType.qualifiedName,
                    name
                ) else null
        }

        else -> null
    }
}

/**
 * `java.io.File("a")`, `kotlin.text.StringBuilder()`: a constructor call spelled with its package. The
 * receiver is then a package path, which is not a value and has no type, so the call went untyped and
 * nothing could be completed or checked after it.
 */
private fun KotlinResolver.fullyQualifiedConstructorCall(q: KtQualifiedExpression): KotlinType? {
    val call = q.selectorExpression as? KtCallExpression ?: return null
    val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    if (callee.firstOrNull()?.isUpperCase() != true) return null
    fun path(e: KtExpression?): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtQualifiedExpression -> {
            val head = path(e.receiverExpression)
            val tail = (e.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (head != null && tail != null) "$head.$tail" else null
        }
        else -> null
    }
    val pkg = path(q.receiverExpression) ?: return null
    val fqn = "$pkg.$callee"
    return if (service.isKnownType(fqn)) constructorResultType(fqn, call) else null
}

/**
 * The type of a reference to a FUNCTION: `KFunctionN<P…, R>` (`KSuspendFunctionN` for a `suspend` one), with the
 * receiver as the first parameter when the reference leaves it unbound (`C::m`, `::ext` of an extension) and not when
 * it binds one (`obj::m`, `C.Companion::f`). Only an unambiguous, non-generic target with known types: an overloaded
 * or generic one is chosen by the expected type, which this does not see.
 */
internal fun KotlinResolver.functionReferenceType(e: KtCallableReferenceExpression): KotlinType? {
    val name = e.callableReference?.getReferencedName() ?: return null
    val target = runCatching { callableReferenceTarget(e) }.getOrNull() ?: return null
    if (target.kind != SymbolKind.METHOD || target.typeParameters.isNotEmpty() || target.varargParamIndex >= 0) return null
    val fn = (target.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtNamedFunction
    val ret = target.type as? KotlinType ?: fn?.let { inferredExpressionBodyType(it) }
        ?: fn?.takeIf { it.hasBlockBody() && it.typeReference == null }?.let { service.typeByFqn("kotlin.Unit") } ?: return null
    val params = target.paramTypes.map { it as? KotlinType ?: return null }
    if (ret.isTypeParameter || params.any { it.isTypeParameter }) return null
    val receiver = e.receiverExpression
    val typeRecv = receiver?.let { typeDenotationFqn(it) }
    // Overloads: the expected type picks one, so the reference has no type of its own here.
    val overloads = if (typeRecv != null) service.membersNamed(typeRecv, emptyList(), name).count { it.kind == SymbolKind.METHOD && it.name == name }
        else if (receiver == null) scopeSymbolsAt(e.textRange.startOffset, name, exactName = true)
            .filter { it.kind == SymbolKind.METHOD && it.name == name }
            .distinctBy { "${it.receiverTypeFqn}(${it.paramTypes.joinToString { p -> p?.qualifiedName.orEmpty() }})" }.size
        else 1
    if (overloads > 1) return null
    val leading = when {
        typeRecv != null && target.owner != null && Modifier.STATIC !in target.modifiers &&
            service.companionMembersFor(typeRecv, name).none { it.name == name } -> listOf(service.typeByFqn(typeRecv))
        receiver == null && target.isExtension -> listOf(service.typeByFqn(target.receiverTypeFqn ?: return null, target.receiverTypeArgs))
        else -> emptyList()
    }
    val all = leading + params
    val suspend = target.isSuspend || fn?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true
    val prefix = if (suspend) "kotlin.reflect.KSuspendFunction" else "kotlin.reflect.KFunction"
    return service.typeByFqn(prefix + all.size, all + ret)
}

/** The type of a callable reference to a property: `KProperty0<T>` when it is bound (`::x`, `obj::x`),
 *  `KProperty1<Owner, T>` when it names the owner (`Owner::x`); the mutable flavours for a `var` declared in
 *  this project. Null for a function reference, which keeps the handling it has elsewhere. */
internal fun KotlinResolver.propertyReferenceType(e: KtCallableReferenceExpression): KotlinType? {
    val target = runCatching { callableReferenceTarget(e) }.getOrNull() ?: return null
    if (target.kind != SymbolKind.FIELD && target.kind != SymbolKind.PARAMETER && target.kind != SymbolKind.LOCAL_VARIABLE) return null
    val valueType = target.type as? KotlinType ?: return null
    val psi = (target.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi
    // A `var` declared either way: a property, or a constructor parameter (`class A(var g: A)`, `A::g`).
    val mutable = (psi as? dev.ide.kotlin.syntax.psi.KtProperty)?.isVar == true ||
        (psi as? dev.ide.kotlin.syntax.psi.KtParameter)?.isMutable == true
    val receiver = e.receiverExpression
    // `Klass<T>::prop`: a type written WITH arguments parses as a call that has no argument list. It names the
    // owner type like a bare `Klass` does, so the reference is unbound (`KMutableProperty1`, `set(receiver, v)`).
    val genericOwner = (receiver as? dev.ide.kotlin.syntax.psi.KtCallExpression)
        ?.takeIf { it.valueArgumentList == null && it.lambdaArguments.isEmpty() && it.typeArgumentList != null }
        ?.let { service.typeFromText(it.text, fileContext, enclosingClassFqnOf(it)) }
    val owner = genericOwner?.qualifiedName ?: receiver?.let { typeDenotationFqn(it) }
    val prefix = if (mutable) "kotlin.reflect.KMutableProperty" else "kotlin.reflect.KProperty"
    if (genericOwner != null) return service.typeByFqn(prefix + "1", listOf(genericOwner, valueType))
    // `A::companionProp` names the COMPANION's property through the class name: it is bound to that one instance.
    val name = e.callableReference?.getReferencedName() ?: ""
    val companionMember = owner != null &&
        service.membersNamed(owner, emptyList(), name).none { it.kind == SymbolKind.FIELD && !it.isExtension } &&
        service.companionMembersFor(owner, name).any { it.name == name && it.kind == SymbolKind.FIELD }
    return if (owner != null && !companionMember) service.typeByFqn(prefix + "1", listOf(service.typeByFqn(owner), valueType))
    else service.typeByFqn(prefix + "0", listOf(valueType))
}

/** A nested type/object reached as `Owner.Nested` — not a member but a classifier; resolved by probing the
 *  candidate nested FQN (kotlinx-metadata and [KotlinSymbolService.isKnownType] both use the dotted form
 *  `pkg.Outer.Inner`, so the result matches the extension index's receiver key). */
internal fun KotlinResolver.nestedType(ownerFqn: String, name: String): KotlinType? =
    "$ownerFqn.$name".takeIf { service.isKnownType(it) }?.let { service.typeByFqn(it) }

internal fun KotlinResolver.typeOfCall(
    call: KtCallExpression,
    receiverType: KotlinType?
): KotlinType? {
    // A callee that is itself an expression (`f()()`, `(impl)(x)`, `list[0]()`): the call is its value's `invoke`.
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        ?: return if (receiverType == null && call.calleeExpression != null) invokeReturnType(call) else null
    // A capitalized selector on an OBJECT receiver: `Codec.Writer(1)` where `object Codec { class Writer }`.
    // An object IS its instance, so `inferType` gives the receiver a type and this reaches the member path --
    // but an object is equally the namespace of its nested classifiers, and nothing named `Writer` is a member
    // there, so the call constructs the nested type. Without this the call typed to nothing, and everything
    // downstream backed off on the unknown type: the members resolved (those checks skip an unknown receiver)
    // while `Codec.Writer(1).run { … }` established no implicit receiver and reported every name in the block
    // unresolved. Gated on the nested type really existing AND no same-named member, so an ordinary member
    // call -- including a factory method with a capitalized name -- is untouched.
    if (receiverType != null && name.firstOrNull()?.isUpperCase() == true) {
        val nested = "${receiverType.qualifiedName}.$name"
        if (service.isKnownType(nested) &&
            service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                .none { it.kind == SymbolKind.METHOD }
        ) return constructorResultType(nested, call)
    }
    // No receiver + Capitalized → a constructor call (the type itself, with its type parameters inferred
    // from the constructor arguments — `Box("s")` → Box<String>). A LOCAL class in scope (registered under a
    // synthetic FQN) is tried first, so `LocalClass()` in a function body types.
    if (receiverType == null && name.firstOrNull()?.isUpperCase() == true) {
        localTypesInScope(call.textRange.startOffset)[name]?.let {
            // Unless it cannot take the call and a same-named class further out can (see [constructorTypeFqn]).
            val target = if (localConstructorTakes(it, call)) it
                else service.resolveTypeName(name, fileContext, null)?.takeIf { o -> o != it && service.isKnownType(o) } ?: it
            return constructorResultType(target, call)
        }
        // An `inner` class of an implicit receiver (`fun A.B.f() = C()`): receiver scopes come before the file's.
        innerClassOfReceiver(name, call.textRange.startOffset)?.let { return constructorResultType(it, call) }
        // The enclosing class is passed so a NESTED type constructed by its simple name from inside the
        // enclosing body types as its own constructor result (`Level(21, "x")` inside
        // `object Api { data class Level(…) }`), matching the bare-name path in `typeOfName`.
        // A project `typealias` constructed by its own name (`DiskFile(path)`) — resolved before the
        // classifier lookup's verbatim fallback, which would hand back the alias name as if it were a type.
        service.typeAliasTargetFqn(name, fileContext)?.let { return constructorResultType(it, call) }
        service.resolveTypeName(name, fileContext, enclosingClassFqn(call.textRange.startOffset))?.let { typeFqn ->
            // A capitalized no-receiver call on a name that resolves to a TYPE is normally a constructor
            // (`Box("s")` → `Box<String>`). Two cases are NOT, and both route to a same-named top-level FACTORY
            // function instead — falling through to the function path below, but ONLY when one actually exists:
            //
            //  * The name resolves to a type that CANNOT be constructed — an `interface`, or an
            //    `abstract`/`sealed` class. The call is then either a SAM / `fun interface` constructor
            //    (`Comparator { … }`, whose result IS the interface type) or a factory shadowing the type
            //    (`fun <T> MutableStateFlow(value: T): MutableStateFlow<T>`; Compose's `FontFamily(vararg Font)`
            //    over the sealed `FontFamily`). Taking the factory also lets its type parameters infer from the
            //    arguments (`T = TextFieldValue`) instead of `constructorResultType` returning the bare,
            //    un-parameterized type. Absent a factory it's the SAM case, so keep the constructor path.
            //  * The name doesn't resolve to a type AT ALL. `resolveTypeName` import-qualifies a bare name
            //    against the file's imports, and a Kotlin import names both classifiers and CALLABLES — so
            //    `import androidx.compose.ui.text.googlefonts.Font` (a package with a top-level `Font(…)` and no
            //    `Font` class) yields a `…googlefonts.Font` that names nothing. Constructing it typed the call as
            //    a non-existent type, which then bound to no parameter anywhere: `FontFamily(Font(googleFont =
            //    …))` lost every `FontFamily` overload and fell back to instantiating the abstract class.
            // An `object` has no constructor: `Inv()` on `object Inv { operator fun invoke(): Int }` is its `invoke`.
            if (service.isSingletonObject(typeFqn)) return invokeReturnType(call)
            val known = service.isKnownType(typeFqn)
            val factoryFunction = service.topLevelByName(name).any { it.kind == SymbolKind.METHOD } &&
                (!known || service.isNonInstantiableType(typeFqn) == true)
            if (!factoryFunction) return constructorResultType(defaultImportedConstructorOver(name, typeFqn, call) ?: typeFqn, call)
        }
    }
    // No function callee → maybe the callee is a VALUE with an `invoke` operator (`val g = Greeter(); g()`).
    val sym = resolveCalleeFunction(call)
        ?: return if (receiverType == null) invokeReturnType(call) else null
    // Bind the function's OWN type parameters from the arguments (listOf("") -> List<String>; a lambda's
    // result binds R in `(…) -> R`), falling back to each parameter's erased bound when an argument can't
    // pin it (a raw `findViewById(): T` → `View`), then substitute into the return type.
    val erasure = methodTypeParamErasure(sym)
    val inferred = inferTypeArguments(sym, call)
    val bindings = (erasure + inferred).toMutableMap()
    applyLowerBounds(sym, call, bindings)
    // Bidirectional constraint inference ([inferCallBindings]) fills any variable the ad-hoc pass above left at
    // just its erased upper bound (or a bare type variable) — e.g. a lambda result flowing through a nested
    // generic, or the expected type driving an argument-less generic. Additive: applied ONLY where the ad-hoc
    // pass didn't confidently pin the variable, so a resolved binding is never overridden.
    if (sym.typeParameters.isNotEmpty()) {
        val expected = expectedTypeAt(call.textRange.startOffset)
        val solved = inferCallBindings(sym, call, expected)
        for ((k, v) in solved) {
            if ((v as? KotlinType)?.isTypeParameter != false) continue
            val cur = bindings[k] as? KotlinType
            val onlyErased = cur == null || cur.isTypeParameter ||
                    (k !in inferred && cur.qualifiedName == (erasure[k] as? KotlinType)?.qualifiedName)
            if (onlyErased) bindings[k] = v
        }
        // A `Unit`-expecting context coerces a lambda-bound return variable to Unit, OVERRIDING what the
        // block's last expression bound it to above ([coerceLambdaReturnVarToUnit]).
        coerceLambdaReturnVarToUnit(sym, call, expected, bindings)
    }
    // The callee's declared return type; when it has none (an expression-body function `fun f() = expr`, whose
    // type neither the same-file symbol nor the disk index carries), infer it from the body.
    // An expression-body function with no written return type returns what its body is, which carries what an
    // indexed summary of it cannot (`fun <F> test(f: F) = f!!` returns `F & Any`); only one whose type mentions a
    // type parameter, the case a summary loses.
    val summarized = sym.type as? KotlinType
    // The body is typed from the declaration's own text, which names the callee's type parameters without marking them.
    val raw = (if (summarized != null && summarized.isTypeParameter && sym.origin.fromSource)
        inferredReturnTypeForCall(call, sym)?.let { service.markTypeParameters(it, sym.typeParameters.toSet()) } else null)
        ?: summarized ?: inferredReturnTypeForCall(call, sym)
    return raw?.let { service.substitute(it, bindings) as? KotlinType }
}

/**
 * The return type of an expression-body function that declares none (`fun greet() = "hi"` → `greet()` is
 * `String`) — inferred from the body so a member chain off the call (`greet().length`) resolves, which the
 * disk index (types stay null there) otherwise left unknown. The callee's live PSI is reached through the
 * resolved symbol; a disk-resolved same-file function falls back to a unique by-name match in the live file.
 * The anonymous-object escape rule ([approximateEscapingLocalType]) is applied, so `fun f() = object { … }`
 * types as `Any` (public) rather than the non-denotable anonymous type. Null when the callee isn't a
 * resolvable same-file expression-body function.
 */
internal fun KotlinResolver.inferredReturnTypeForCall(
    call: KtCallExpression,
    sym: KotlinSymbol
): KotlinType? {
    (sym.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi?.let { psi ->
        (psi as? KtNamedFunction)?.let { return inferredExpressionBodyType(it) }
    }
    // A member call is resolved elsewhere; only a bare top-level call needs the live-file by-name fallback.
    if ((call.parent as? KtQualifiedExpression)?.selectorExpression === call) return null
    val name =
        (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    val matches = ktFile.declarations.filterIsInstance<KtNamedFunction>()
        .filter { it.name == name && it.receiverTypeReference == null && it.typeReference == null && !it.hasBlockBody() }
    return matches.singleOrNull()?.let { inferredExpressionBodyType(it) }
}

/** The type of [fn]'s expression body, with the anonymous-object escape approximation applied and a
 *  re-entrancy guard (see [KotlinResolver.inferringReturnBodies]). Null for a block body / declared-type
 *  function (the declared type covers those) or an un-typeable body. */
internal fun KotlinResolver.inferredExpressionBodyType(fn: KtNamedFunction): KotlinType? {
    if (fn.hasBlockBody() || fn.typeReference != null) return null
    val body = fn.bodyExpression ?: return null
    if (!inferringReturnBodies.add(fn)) return null
    return try {
        inferType(body)?.let { approximateEscapingLocalType(it, fn) }
    } finally {
        inferringReturnBodies.remove(fn)
    }
}

/**
 * Kotlin approximates the inferred return/property type of a NON-local, NON-private declaration to a
 * denotable supertype when the body's type is an anonymous-object / local type — that type isn't nameable
 * outside the scope it's declared in. So `fun giveMe() = object { val player = "x" }` returns `Any` (its
 * anonymous type has no declared supertype), and `giveMe().player` is unresolved, matching the compiler. A
 * LOCAL or PRIVATE declaration keeps the exact anonymous type (its members stay accessible in that scope).
 */
internal fun KotlinResolver.approximateEscapingLocalType(
    t: KotlinType,
    decl: KtDeclaration
): KotlinType {
    if (isLocalDeclaration(decl)) return t
    // A private declaration keeps the anonymous type, unless it is `inline`: its body is copied into callers that
    // cannot name the type (K2's `FirDeclarationUtil.isLocalOrPrivateAndNotInline`, KT-33917).
    if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD) && !decl.hasModifier(KtTokens.INLINE_KEYWORD)) return t
    return approximateLocalTypeDeep(t, 0)
}

/** [t] with every local/anonymous type in it, nested type arguments included (`Inv<anonymous>` becomes `Inv<Any>`),
 *  replaced by its denotable supertype. */
private fun KotlinResolver.approximateLocalTypeDeep(t: KotlinType, depth: Int): KotlinType {
    if (isSyntheticLocalTypeFqn(t.qualifiedName)) {
        val supers = service.supertypesOf(t.qualifiedName).filterIsInstance<KotlinType>()
            .filter { it.qualifiedName != t.qualifiedName }
        val approx = supers.firstOrNull { it.qualifiedName != "kotlin.Any" && it.qualifiedName != "java.lang.Object" }
            ?: service.typeByFqn("kotlin.Any")
        return if (t.nullable) approx.withNullable(true) else approx
    }
    if (depth > 4 || t.typeArguments.none { a -> (a as? KotlinType)?.let { mentionsLocalType(it, 0) } == true }) return t
    if (t.isTypeParameter || t.isExtensionFunctionType || t.isComposable) return t
    val args = t.typeArguments.map { a ->
        (a as? KotlinType)?.let { approximateLocalTypeDeep(it, depth + 1).withProjection(it.projection) } ?: a
    }
    return service.typeByFqn(t.qualifiedName, args).withNullable(t.nullable).withProjection(t.projection)
}

private fun mentionsLocalType(t: KotlinType, depth: Int): Boolean =
    isSyntheticLocalTypeFqn(t.qualifiedName) ||
        (depth < 5 && t.typeArguments.any { a -> (a as? KotlinType)?.let { mentionsLocalType(it, depth + 1) } == true })

/** Whether [fqn] is the synthetic key a local/anonymous type is registered under ([SourceIndexBuilder.
 *  localTypeFqn] appends a `$L<ordinal>` last segment). */
internal fun isSyntheticLocalTypeFqn(fqn: String): Boolean =
    fqn.substringAfterLast('.').startsWith("\$L")

/** Whether [decl] is declared inside a body (not a top-level or class-member declaration) — an anonymous
 *  type it returns stays denotable in its enclosing scope, so no escape approximation applies. */
internal fun isLocalDeclaration(decl: KtDeclaration): Boolean {
    val parent = decl.parent
    if (parent is KtClassBody) {
        // A member of a LOCAL or anonymous class is not visible outside the local scope either, so it keeps the
        // anonymous type too (`val a = object { val b = object { val c = 42 } }; a.b.c`).
        val owner = parent.parent
        return owner is dev.ide.kotlin.syntax.psi.KtObjectDeclaration && owner.parent is dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression ||
            (owner as? dev.ide.kotlin.syntax.psi.KtElement)?.getStrictParentOfType<dev.ide.kotlin.syntax.psi.KtBlockExpression>() != null
    }
    return parent !is KtFile
}

/**
 * The type a constructor call `Foo(args)` produces, with `Foo`'s type parameters inferred from the
 * constructor arguments (`Box("s")` → `Box<String>`), so a chain off it (`Box("s").value`) and the scope
 * function receiver (`Box("s").apply { value }`) resolve the element type. Falls back to the raw type when
 * `Foo` is non-generic, has no matching constructor, or no argument pins a parameter; an unbound parameter
 * (a partial inference) erases to `Any`.
 */
internal fun KotlinResolver.constructorResultType(fqn: String, call: KtCallExpression): KotlinType {
    // `Array(size) { init }` / `Array<T>(size)` — the element type comes from the explicit type argument, else
    // the init lambda's RESULT (the intrinsic constructor `Array<T>(size: Int, init: (Int) -> T)`). Handled
    // before the type-parameter check below, which the service leaves empty for the built-in `Array`.
    if (fqn == "kotlin.Array") {
        call.typeArgumentList?.arguments?.firstOrNull()?.typeReference?.text
            ?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(call)) }
            ?.let { return service.typeByFqn(fqn, listOf(it)) }
        val initLambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: call.valueArguments.mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
                .lastOrNull()
        initLambda?.let { inferLambdaResult(it) }?.let { return service.typeByFqn(fqn, listOf(it)) }
    }
    val tps = service.classTypeParameters(fqn)
    if (tps.isEmpty()) return service.typeByFqn(fqn)
    // Explicit type arguments on the call (`ArrayList<String>()`, `HashMap<String, Int>()`, `Box<Int>()`) are
    // used verbatim — Kotlin does no argument inference when they're spelled out. Take them positionally.
    call.typeArgumentList?.arguments?.takeIf { it.isNotEmpty() }?.let { targs ->
        val args = tps.mapIndexed { i, _ ->
            targs.getOrNull(i)?.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(call)) }
                ?: service.typeByFqn("kotlin.Any")
        }
        return service.typeByFqn(fqn, args)
    }
    samConstructorResultType(fqn, tps, call)?.let { return it }
    // A library class with several constructors of this arity (`HashMap(int)` and `HashMap(Map)`) binds its
    // type parameters through the one the arguments fit, chosen as any overload is; the first of the arity
    // left `HashMap(m)` a `HashMap<Any, Any>` whose `get` read as `Object`.
    val paramTypes = (if (service.sourceClass(fqn) == null) {
        service.constructorsOf(fqn)
            .filter { Modifier.PRIVATE !in it.modifiers && !it.isInternal && it.paramTypes.size == call.valueArguments.size }
            .takeIf { it.size > 1 }
            ?.let { bestOverload(it, call, null).paramTypes }
    } else null)
        ?: service.constructorParamTypes(fqn, call.valueArguments.size)
        ?: return service.typeByFqn(fqn)
    val bindings = HashMap<String, TypeRef>()
    call.valueArguments.forEachIndexed { i, arg ->
        val pt = paramTypes.getOrNull(i) as? KotlinType ?: return@forEachIndexed
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        if (expr is KtLambdaExpression) return@forEachIndexed // a lambda arg can't pin a scalar type param here
        inferType(expr)?.let { unify(pt, it, bindings) }
    }
    // A type parameter whose only constraint is a `null` passed for its `T?` parameter (`Query(null, null, true)`)
    // has the lower bound `Nothing`, which K2 fixes it to; an expected type of the same class supplies it instead.
    call.valueArguments.forEachIndexed { i, arg ->
        val pt = paramTypes.getOrNull(i) as? KotlinType ?: return@forEachIndexed
        if (!pt.isTypeParameter || !pt.nullable || pt.qualifiedName !in tps || pt.qualifiedName in bindings) return@forEachIndexed
        val e = arg.getArgumentExpression()?.let { unwrapParens(it) } ?: return@forEachIndexed
        if (e !is dev.ide.kotlin.syntax.psi.KtConstantExpression || e.text != "null") return@forEachIndexed
        val expected = expectedTypeAt(call.textRange.startOffset)
            ?.takeIf { it.qualifiedName == fqn && it.typeArguments.size == tps.size }
        bindings[pt.qualifiedName] = expected?.typeArguments?.get(tps.indexOf(pt.qualifiedName))
            ?.takeIf { (it as? KotlinType)?.isTypeParameter == false } ?: service.typeByFqn("kotlin.Nothing")
    }
    if (bindings.isEmpty()) return service.typeByFqn(fqn)
    return service.typeByFqn(fqn, tps.map { bindings[it] ?: service.typeByFqn("kotlin.Any") })
}

/**
 * The type of a generic SAM-constructor call with no explicit type arguments: `Callable { 42 }` is a
 * `Callable<Int>`, from the lambda's RESULT bound against the single abstract method's return; and
 * `Comparator { a, b -> … }` where a `Comparator<String>` is expected takes the expected arguments. An
 * interface has no constructor, so the ordinary argument path left every parameter unbound and the call
 * erased to `Callable<Any>`, which typed `Callable { 42 }.call()` as `Object`. Null when [fqn] is not an
 * interface or the call has no lambda.
 */
private fun KotlinResolver.samConstructorResultType(fqn: String, tps: List<String>, call: KtCallExpression): KotlinType? {
    if (service.isInterfaceType(fqn) != true) return null
    val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
        ?: call.valueArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression
        ?: return null
    expectedTypeAt(call.textRange.startOffset)
        ?.takeIf { sameSimpleName(it.qualifiedName, fqn) && it.typeArguments.size == tps.size && concreteArgs(it) }
        ?.let { return service.typeByFqn(fqn, it.typeArguments) }
    val generic = service.typeByFqn(fqn, tps.map { KotlinType(it, isTypeParameter = true) })
    val ret = service.functionalShape(generic)?.returnType as? KotlinType ?: return null
    val body = inferLambdaResult(lambda) as? KotlinType ?: return null
    val bindings = HashMap<String, TypeRef>()
    unify(ret, body, bindings)
    if (bindings.isEmpty()) return null
    return service.typeByFqn(fqn, tps.map { bindings[it] ?: service.typeByFqn("kotlin.Any") })
}

/** `value(args)` where `value` isn't a function — the result of its `invoke` operator (`Greeter()(name)`,
 *  a functional object / DSL). The argument count picks the matching `invoke` overload. Null when the callee
 *  has no inferable type or no applicable `invoke`. */
internal fun KotlinResolver.invokeReturnType(call: KtCallExpression): KotlinType? {
    val calleeType = inferType(call.calleeExpression)?.takeIf { !it.isTypeParameter } ?: return null
    // A directly function-typed value (`val f = fun(x: Int) = x; f(3)`, a lambda held in a var) — its result is
    // the function type's `R`, which the `kotlin.FunctionN` classifier carries as its last type argument.
    // A SAM interface has a functional shape too, and when its single abstract method is a GENERIC `invoke`
    // that `R` is the METHOD's own type parameter — unbound here. Fall through to the member path below, which
    // binds it from the arguments; a function type whose `R` is a type parameter reaches the same answer there.
    (service.functionalShape(calleeType)?.returnType as? KotlinType)
        ?.takeIf { !it.isTypeParameter }?.let { return it }
    val n = call.valueArguments.size
    val invokes = service.membersNamed(calleeType.qualifiedName, calleeType.typeArguments, "invoke")
        .filter { it.kind == SymbolKind.METHOD }
    val invoke = invokes.firstOrNull { it.paramTypes.size == n } ?: invokes.firstOrNull() ?: return null
    return operatorResultType(invoke, call.valueArguments.map { it.getArgumentExpression() })
}

internal fun KotlinResolver.typeOfName(name: String, offset: Int): KotlinType? {
    narrowedType(name)?.let { return it } // the lowerer's explicit flow-narrowing stack
    // Editor smart cast: an `if (x is T)` guard narrows `x` to `T` here (completion + diagnostics both
    // resolve a receiver through inferType -> typeOfName). Off while the lowerer drives the stack above,
    // so the interpreter keeps its own authoritative flow-narrowing.
    if (narrowings.isEmpty()) smartCastTypeAt(name, offset)?.let { return it }
    localsAt(offset, name).firstOrNull()?.let { return it.type as? KotlinType }
    // Members of any implicit `this` (apply/with/run block, extension fn, enclosing class).
    val receivers = implicitReceiversAt(offset)
    for (level in receivers.indices) {
        val recv = receivers[level]
        // For a same-file class PROPERTY prefer the live PSI inference: the source model types an implicit-typed
        // member from a crude parse of the initializer's callee name (`val x = MutableStateFlow(Key())` →
        // raw `MutableStateFlow`), dropping the generic argument, whereas [sameFileProperty] runs full
        // `inferType` on the initializer and keeps it (`MutableStateFlow<Key>`). Only for a FIELD — a function
        // reference keeps the model's shape.
        val live = sameFileTypeMember(recv.qualifiedName, name)?.takeIf { it.kind == SymbolKind.FIELD }
        (live?.type as? KotlinType)?.let { return it }
        (memberNamed(recv, name)?.type as? KotlinType)?.let { return it }
        // A member extension property of an enclosing class, taking this receiver as its extension receiver
        // (`val String.x` declared in the class, read bare inside `with(s) { x }`): K2's tower tries the
        // extensions of a receiver level before the members of the receivers outside it.
        if (level < receivers.size - 1 && mayBeMemberExtensionProperty(name)) {
            scopeMemberExtensions(offset, recv, name, exactName = true)
                .firstOrNull { it.name == name && it.kind == SymbolKind.FIELD }
                ?.let { (it.type as? KotlinType)?.let { t -> return t } }
        }
    }
    // A bare read of an enclosing class's COMPANION member (`fun f() = CONST` inside the class) — companion
    // members are accessible without a qualifier, but live on a distinct classifier the receiver walk misses.
    enclosingCompanionMember(name, offset)?.let { return it.type as? KotlinType }
    // An entry of an enclosing enum read bare inside it (`IN` in `enum class VPos { …; fun flip() = … IN … }`): the
    // enum's static scope holds its entries, each a value of the enum type.
    if (name.firstOrNull()?.isUpperCase() == true) {
        var node: dev.ide.kotlin.syntax.psi.KtElement? = elementAt(offset)
        while (node != null) {
            if (node is dev.ide.kotlin.syntax.psi.KtClass && node.isEnum() &&
                node.declarations.any { it is dev.ide.kotlin.syntax.psi.KtEnumEntry && it.name == name }
            ) {
                val fqn = node.fqName?.asString() ?: dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(node)
                return service.typeByFqn(fqn)
            }
            node = node.parent as? dev.ide.kotlin.syntax.psi.KtElement
        }
    }
    // A bare name brought into scope by an explicit member import (`import …MainActivity.Companion.TAG`,
    // `import …Config.DEBUG`) — a companion-object / object member is accessible unqualified once imported, so
    // a chain off it (`TAG.length`) can resolve. Gated on a matching non-star import simple name (the common
    // case is zero matches), so the member lookup only fires for a name that was actually imported.
    for (imp in fileContext.imports) {
        if (imp.isStar || imp.simpleName != name) continue
        service.importedMemberSymbol(imp.fqn)?.let { return it.type as? KotlinType }
    }
    // A same-file TOP-LEVEL property: infer its type from the initializer via live PSI (`sameFileProperty`
    // runs full `inferType`), NOT the disk model's crude callee-name parse — which mistypes a builder chain
    // `Retrofit.Builder().…​.build()` as its FIRST callee `Retrofit.Builder` (a known type without the built
    // type's members → the reported `a.create()` false "unresolved"). Mirrors the same-file MEMBER preference
    // above; a cross-file top-level property still falls through to the model type below.
    ktFile.declarations.firstOrNull { it is KtProperty && it.receiverTypeReference == null && it.name == name }
        ?.let { return sameFileProperty(it as KtProperty, null).type as? KotlinType }
    // Only one that is IN SCOPE (imported, same package, default-imported): `kotlin.math.E` is a top-level
    // property too, and without the scope test a project `enum class E` read as that `Double` everywhere.
    run {
        var best: dev.ide.lang.kotlin.symbols.KotlinSymbol? = null
        var bestRank = Int.MAX_VALUE
        for (s in service.topLevelByName(name)) {
            if (s.kind != SymbolKind.FIELD || !topLevelInScope(s, fileContext)) continue
            if (best == null) { best = s; continue } // a single candidate (the usual case) needs no ranking
            if (bestRank == Int.MAX_VALUE) bestRank = topLevelScopeRank(best, fileContext)
            val rank = topLevelScopeRank(s, fileContext)
            if (rank < bestRank) { best = s; bestRank = rank }
        }
        best?.let { return it.type as? KotlinType }
    }
    // A bare type name used as an expression (e.g. `Foo` in `Foo.CONST`): a LOCAL type in scope first (a local
    // `object`/`class` the module model registered under a synthetic FQN), else a resolvable type name. The
    // ENCLOSING class is passed so a NESTED one reached by its simple name resolves (`object Inner` referenced
    // as `Inner` from inside `class Host`: `with(Inner) { }`, `Inner.plain()`, `val x = Inner`). Without it
    // the name resolved to nothing, so a `with(Inner) { … }` block had no receiver and every reference in it
    // read as unresolved. Mirrors the unresolved-TYPE check, which already passes it.
    if (name.firstOrNull()?.isUpperCase() == true) {
        localTypesInScope(offset)[name]?.let { return service.typeByFqn(it) }
        // A value a star import of a class brings in (`import TestEnum.*` then `val x = Annotation`): an explicit
        // star import outranks the default imports, whose `kotlin.Annotation` classifier is no value anyway.
        importedSingletonMembers(name, exactName = true)
            .firstOrNull { it.name == name && (it.kind == SymbolKind.ENUM_CONSTANT || it.kind == SymbolKind.FIELD) }
            ?.let { (it.type as? KotlinType)?.let { t -> return t } }
        service.resolveTypeName(name, fileContext, enclosingClassFqn(offset))
            ?.let { return service.typeByFqn(it) }
    }
    return null
}

/** Whether a class of this file or of the project declares a member extension property named [name]: the gate in
 *  front of the receiver-level extension lookup in [typeOfName]. */
internal fun KotlinResolver.mayBeMemberExtensionProperty(name: String): Boolean {
    val live = caches.memberExtensionPropertyNames ?: HashSet<String>().also { out ->
        fun visit(e: KtElement) {
            if (e is KtProperty && e.receiverTypeReference != null && e.parent is dev.ide.kotlin.syntax.psi.KtClassBody) e.name?.let { out += it }
            var c = e.firstChild
            while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
        }
        visit(ktFile)
        caches.memberExtensionPropertyNames = out
    }
    return name in live || service.isMemberExtensionPropertyName(name)
}

internal fun KotlinResolver.typeOfTypeName(expr: KtExpression): KotlinType? {
    val name = (expr as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    if (name.firstOrNull()?.isUpperCase() != true) return null
    return service.resolveTypeName(name, fileContext)?.let { service.typeByFqn(it) }
}

/**
 * A class name used as a VALUE (`build(Widget)`, `val f: Factory<Widget> = Widget`, `return Widget`) denotes the
 * class's companion object, so it has the companion's type. It typed as the class itself, and passing a
 * companion that implements an interface read "inferred type is Widget but Factory<T> was expected". Only the
 * plain value slots are recognized: a receiver (`Widget.create()`) keeps the type denotation that static,
 * nested and enum lookups are built on. Null when [expr] names no class with a companion.
 */
private fun KotlinResolver.companionValueType(expr: KtNameReferenceExpression): KotlinType? {
    if (expr.getReferencedName().firstOrNull()?.isUpperCase() != true) return null
    val inValueSlot = when (val p = expr.parent) {
        is KtValueArgument -> true
        is KtProperty -> p.initializer === expr
        is KtReturnExpression -> p.returnedExpression === expr
        else -> false
    }
    if (!inValueSlot) return null
    val fqn = typeDenotationFqn(expr) ?: return null
    val companion = service.companionObjectFqn(fqn) ?: return null
    return service.typeByFqn(companion)
}

/** `X::class` → `kotlin.reflect.KClass<X>`. The left-hand side is either a classifier name (`Main::class`,
 *  `Foo.Bar::class`, `String::class`) or a value expression (`instance::class`); either way its type is the
 *  literal's single type argument. So `Main::class.java` resolves the `KClass<T>.java` extension to
 *  `Class<Main>`, and `Main::class.simpleName` reaches a `KClass` member. The argument is made non-null
 *  (`KClass`'s parameter is `T : Any`); a bare/reified type-parameter LHS leaves it open (raw `KClass`). */
internal fun KotlinResolver.classLiteralType(e: KtClassLiteralExpression): KotlinType {
    val lhs = e.receiverExpression
    val arg = lhs?.let { inferType(it) ?: typeOfTypeName(it) }?.withNullable(false)
    return service.typeByFqn("kotlin.reflect.KClass", listOfNotNull(arg))
}

internal fun KotlinResolver.memberNamed(type: KotlinType, name: String): KotlinSymbol? {
    // An intersection (`B & A`) has the members of every component.
    val all = service.membersNamed(type.qualifiedName, type.typeArguments, name)
        .ifEmpty { type.intersection.firstNotNullOfOrNull { c -> service.membersNamed(c.qualifiedName, c.typeArguments, name).takeIf { it.isNotEmpty() } }.orEmpty() }
    // A property read wants a PROPERTY: `t.priority` is `Thread`'s `getPriority()` property, not its
    // package-private `void priority(int)` method, which typed the read `Unit`. And a public one before a
    // protected field: `Calendar`'s `protected long time` is out of reach where `c.time` is the `Date` that
    // `getTime()`/`setTime(Date)` expose. Any member is still the fallback, as before.
    val fields = all.filter { it.kind == SymbolKind.FIELD }
    return fields.firstOrNull { Modifier.PROTECTED !in it.modifiers } ?: fields.firstOrNull() ?: all.firstOrNull()
}

/** The type of an anonymous object (`object : Foo { }`, `object { val a = 1 }`): the synthetic classifier its
 *  declaration was registered under in the source model ([SourceIndexBuilder.localTypeFqn]), computed from the
 *  same PSI the model captured, so `x.member` / members inside the body enumerate through the normal machinery. */
internal fun KotlinResolver.objectLiteralType(e: KtObjectLiteralExpression): KotlinType {
    // `object` with nothing after it parses to a literal with no declaration, so there is no classifier to
    // have registered. PSI declared this non-null; the facade does not, and unknown is the honest answer.
    val decl = e.objectDeclaration ?: return service.typeByFqn("")
    return service.typeByFqn(dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(decl))
}

/**
 * The `(P…) -> R` function type of an anonymous function `fun(p: P): R = …` (optionally an extension
 * `fun P.(…) -> R`). Value-parameter types come from the (required) declared types; the result is the declared
 * return, else `Unit` for a block body, else the inferred expression-body type (falling back to `Unit`). Null
 * only when a value parameter or the receiver has no resolvable type — then the value degrades to untyped
 * rather than a wrong shape.
 */
internal fun KotlinResolver.anonymousFunctionType(fn: KtNamedFunction): KotlinType? {
    val paramTypes = fn.valueParameters.map { p ->
        service.typeFromText(p.typeReference?.text, fileContext) ?: return null
    }
    val recvType =
        fn.receiverTypeReference?.text?.let { service.typeFromText(it, fileContext) ?: return null }
    val returnType = service.typeFromText(fn.typeReference?.text, fileContext)
        ?: (if (fn.hasBlockBody()) service.typeByFqn("kotlin.Unit") else inferType(fn.bodyExpression))
        ?: service.typeByFqn("kotlin.Unit")
    val isExtension = recvType != null
    val arity = (if (isExtension) 1 else 0) + paramTypes.size
    val args = listOfNotNull(recvType) + paramTypes + returnType
    return KotlinType(
        "kotlin.Function$arity",
        args,
        nullable = false,
        context = service,
        isExtensionFunctionType = isExtension
    )
}

/** A companion-object member's type for a `Type.member` access (`MyClass.CONST`, `MyClass.factory`): resolved
 *  against `Type`'s companion, which is a distinct classifier the instance member lookup never sees. Null when
 *  `Type` has no companion member of that name. */
internal fun KotlinResolver.companionMemberType(ownerFqn: String, name: String): KotlinType? =
    service.companionMembersFor(ownerFqn, name).firstOrNull { it.name == name }?.type as? KotlinType
