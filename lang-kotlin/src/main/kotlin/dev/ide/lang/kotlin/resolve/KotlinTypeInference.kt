package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

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
    // A narrowed type is flow-dependent, not a property of the expression alone — bypass the cache so it
    // can't leak across narrowing scopes.
    val cacheable = narrowings.isEmpty()
    if (cacheable && inferCache.containsKey(expr)) return inferCache[expr]
    if (KotlinResolverStats.enabled) KotlinResolverStats.inferComputes++
    val r = when (expr) {
        is KtParenthesizedExpression -> inferType(expr.expression)
        is KtConstantExpression -> constType(expr)
        is KtStringTemplateExpression -> service.typeByFqn("kotlin.String")
        is KtNameReferenceExpression -> typeOfName(expr.getReferencedName(), expr.textRange.startOffset)
        is KtCallExpression -> typeOfCall(expr, null)
        is KtQualifiedExpression -> typeOfQualified(expr)
        is KtClassLiteralExpression -> classLiteralType(expr)
        is KtThisExpression -> thisType(expr)
        is KtSuperExpression -> superType(expr)
        is KtBinaryExpression -> inferBinaryType(expr)
        is KtBinaryExpressionWithTypeRHS -> castType(expr)
        is KtPrefixExpression -> inferPrefixType(expr)
        is KtArrayAccessExpression -> inferArrayGet(expr)
        // `if`/`when` used as an expression: the value is one of the branches, so its type is the common
        // type of the branches — made nullable if any branch is `null` (`fun f(): E? = when(x){ a->E.A;
        // else->null }`). (A common Compose pattern: `Icon(if (selected) IconA else IconB, …)` — typing the
        // arg disambiguates the overload.)
        is KtIfExpression -> inferBranchUnion(listOf(expr.then, expr.`else`))
        is KtWhenExpression -> inferBranchUnion(expr.entries.map { it.expression })
        else -> null
    }
    if (cacheable) inferCache[expr] = r
    return r
}

/** Infer the result type of a binary operator: comparison/equality/logical → `Boolean`; elvis → the
 *  non-null left (or the right); arithmetic → numeric promotion for the primitive number types (the builtins
 *  model carries no return type for `Int.times`/etc.), else the operator member's return type, with string
 *  `+` short-circuited to `String` (so `"x" + y` types even when `String.plus` isn't in the member index). */
internal fun KotlinResolver.inferBinaryType(e: KtBinaryExpression): KotlinType? = when (val token = e.operationToken) {
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
        if (token == KtTokens.PLUS && leftType.qualifiedName == "kotlin.String") leftType
        // Primitive numeric arithmetic (`progress * 100`): the result is the WIDER of the two operands
        // (Double > Float > Long > Int; Byte/Short/Char promote to Int) — Kotlin's promotion. Computed
        // directly because the builtin `Float.times`/etc. members carry no return type in the model, so the
        // member lookup below returns null and `(progress * 100).toInt()` couldn't resolve its receiver.
        else numericResultType(leftType, inferType(e.right))
            ?: service.membersNamed(leftType.qualifiedName, leftType.typeArguments, convention)
                .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
                ?.type as? KotlinType
    }
}

/** The return type of a single-argument binary CONVENTION call `left <name> right` — a custom `infix fun`,
 *  `to`/`until`, or a `..`/`..<` desugaring to `rangeTo`/`rangeUntil`. Resolved as the single-param member of
 *  the left type (member-first), else a single-param extension on it, with the callable's own type parameters
 *  bound from the right operand (`"" to 1` → `Pair<String, Int>`). Null when unresolved. */
internal fun KotlinResolver.binaryConventionReturn(e: KtBinaryExpression, name: String): KotlinType? {
    val leftType = inferType(e.left) ?: return null
    val member = service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
    val callable = member ?: service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name, exactName = true)
        .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
    val raw = callable?.type as? KotlinType
    if (raw == null || callable.typeParameters.isEmpty()) return raw
    val bindings = HashMap<String, TypeRef>()
    (callable.paramTypes.firstOrNull() as? KotlinType)?.let { pt -> inferType(e.right)?.let { unify(pt, it, bindings) } }
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
    val t = service.typeFromText(raw.removeSuffix("?"), fileContext) ?: return null
    return if (nullable) t.withNullable(true) else t
}

/** Kotlin numeric-promotion result type for arithmetic between primitive numbers: the wider operand, with
 *  Byte/Short/Char promoted to `Int`. Null when [left] isn't a primitive number (so a non-numeric operator
 *  overload falls back to its declared return type). An unknown [right] doesn't pull the result below [left]. */
internal fun KotlinResolver.numericResultType(left: KotlinType, right: KotlinType?): KotlinType? {
    val lr = NUMERIC_RANK[left.qualifiedName] ?: return null
    val rr = right?.qualifiedName?.let { NUMERIC_RANK[it] } ?: -1
    val rank = maxOf(lr, rr).coerceAtLeast(NUMERIC_RANK.getValue("kotlin.Int"))
    return service.typeByFqn(NUMERIC_RANK.entries.first { it.value == rank }.key)
}

/** The value of an `if`/`when` branch: a bare expression as-is, or the last statement of a `{ … }` block
 *  branch (so `if (c) { x } else { y }` still types). Null when the branch is absent or not an expression. */
internal fun KotlinResolver.branchExpr(branch: KtExpression?): KtExpression? = when (branch) {
    is KtBlockExpression -> branch.statements.lastOrNull() as? KtExpression
    else -> branch
}

/** The type of an `if`/`when` used as an expression: the common classifier of its branches, made nullable
 *  if any branch is `null` (or itself nullable). Conservative — if the typeable branches disagree on
 *  classifier, or any branch's type is unknown, returns null so the type-mismatch check backs off rather
 *  than guess a least-upper-bound (which could false-positive against a more specific declared type). */
internal fun KotlinResolver.inferBranchUnion(branches: List<KtExpression?>): KotlinType? {
    var common: KotlinType? = null
    var nullable = false
    for (b in branches) {
        val e = branchExpr(b) ?: continue
        if (isNullBranch(e)) { nullable = true; continue }
        val t = inferType(e) ?: return null // an unknown branch → can't confidently unify
        if (t.nullable) nullable = true
        if (common == null) common = t
        else if (t.qualifiedName != common.qualifiedName) return null // branches disagree → give up
    }
    return common?.withNullable(nullable)
}

internal fun KotlinResolver.isNullBranch(e: KtExpression): Boolean = e is KtConstantExpression && e.text.trim() == "null"

/** `xs[i]` → the element type: the (substituted) return type of the receiver's `get(index)` operator
 *  (`List<String>.get` → `String`, `Map<K,V>.get` → `V?`). Null when the receiver type or `get` is unknown. */
internal fun KotlinResolver.inferArrayGet(e: KtArrayAccessExpression): KotlinType? {
    val recv = inferType(e.arrayExpression) ?: return null
    val arity = e.indexExpressions.size
    return service.membersNamed(recv.qualifiedName, recv.typeArguments, "get")
        .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.size == arity }
        ?.type as? KotlinType
}

/** `this` (optionally `this@Label`) → the matching implicit receiver in scope (innermost when unlabeled). */
internal fun KotlinResolver.thisType(expr: KtThisExpression): KotlinType? {
    val receivers = implicitReceiversAt(expr.textRange.startOffset)
    val label = expr.getLabelName()
    return if (label == null) receivers.firstOrNull()
    else receivers.firstOrNull { it.qualifiedName.substringAfterLast('.') == label }
}

/** `super` (optionally `super<Base>`) → the enclosing class's supertype, so `super.member` lists inherited
 *  members. With no explicit supertype, `kotlin.Any` (whose members `toString`/`hashCode`/… are inherited). */
internal fun KotlinResolver.superType(expr: KtSuperExpression): KotlinType? {
    expr.superTypeQualifier?.text?.let { service.typeFromText(it, fileContext)?.let { t -> return t } }
    val cls = enclosingClassOrObject(expr.textRange.startOffset) ?: return null
    val superText = cls.superTypeListEntries.firstNotNullOfOrNull { it.typeReference?.text }
    return superText?.let { service.typeFromText(it, fileContext) } ?: service.typeByFqn("kotlin.Any")
}

internal fun KotlinResolver.constType(e: KtConstantExpression): KotlinType? {
    val t = e.text.trim()
    return service.typeByFqn(
        when {
            t == "true" || t == "false" -> "kotlin.Boolean"
            t == "null" -> return null
            t.startsWith("'") -> "kotlin.Char"
            // A radix-prefixed integer literal: its a-f / e digits are NOT a float exponent/suffix, and a
            // value that overflows Int widens to Long (so a 32-bit ARGB hex like `0xFFD32F2F` types as Long
            // → the `Color(Long)` overload, not `Color(Int)`/Double).
            t.startsWith("0x", true) || t.startsWith("0b", true) -> hexBinType(t)
            t.endsWith("L") || t.endsWith("l") -> "kotlin.Long"
            t.endsWith("f") || t.endsWith("F") -> "kotlin.Float"
            '.' in t || 'e' in t || 'E' in t -> "kotlin.Double"
            else -> if ((t.replace("_", "").toLongOrNull() ?: 0L) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "kotlin.Int" else "kotlin.Long"
        },
    )
}

/** The type of a hex/binary integer literal — `Long` with an `L` suffix or when the value overflows `Int`. */
internal fun KotlinResolver.hexBinType(raw: String): String {
    val radix = if (raw[1].lowercaseChar() == 'x') 16 else 2
    var body = raw.substring(2).replace("_", "")
    val isLong = body.endsWith("L") || body.endsWith("l")
    if (isLong) body = body.dropLast(1)
    if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1)
    val v = body.toLongOrNull(radix) ?: body.toULongOrNull(radix)?.toLong() ?: return "kotlin.Int"
    return if (isLong || v !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "kotlin.Long" else "kotlin.Int"
}

internal fun KotlinResolver.typeOfQualified(q: KtQualifiedExpression): KotlinType? {
    // A fully-qualified type used directly (`android.R`, `java.util.Locale`) — resolve the whole text.
    if (service.isKnownType(q.text)) return service.typeByFqn(q.text)
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
    val receiverType = inferType(q.receiverExpression)
        ?: typeOfTypeName(q.receiverExpression) // receiver may be a class name (companion/static access)
        ?: return null
    return when (val sel = q.selectorExpression) {
        is KtCallExpression -> typeOfCall(sel, receiverType)
        is KtNameReferenceExpression -> {
            val name = sel.getReferencedName()
            // A member property/field, else a NESTED type/object reached through its enclosing type
            // (`Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`) — a classifier, not a member, resolved by
            // probing the candidate nested FQN (so an icon extension-property receiver
            // `Icons.AutoMirrored.Filled.List` types and the property binds as an extension).
            memberNamed(receiverType, name)?.type as? KotlinType
                ?: nestedType(receiverType.qualifiedName, name)
        }
        else -> null
    }
}

/** A nested type/object reached as `Owner.Nested` — not a member but a classifier; resolved by probing the
 *  candidate nested FQN (kotlinx-metadata and [KotlinSymbolService.isKnownType] both use the dotted form
 *  `pkg.Outer.Inner`, so the result matches the extension index's receiver key). */
internal fun KotlinResolver.nestedType(ownerFqn: String, name: String): KotlinType? =
    "$ownerFqn.$name".takeIf { service.isKnownType(it) }?.let { service.typeByFqn(it) }

internal fun KotlinResolver.typeOfCall(call: KtCallExpression, receiverType: KotlinType?): KotlinType? {
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    // No receiver + Capitalized → a constructor call (the type itself, with its type parameters inferred
    // from the constructor arguments — `Box("s")` → Box<String>).
    if (receiverType == null && name.firstOrNull()?.isUpperCase() == true) {
        service.resolveTypeName(name, fileContext)?.let { return constructorResultType(it, call) }
    }
    // No function callee → maybe the callee is a VALUE with an `invoke` operator (`val g = Greeter(); g()`).
    val sym = resolveCalleeFunction(call) ?: return if (receiverType == null) invokeReturnType(call) else null
    // Bind the function's OWN type parameters from the arguments (listOf("") -> List<String>; a lambda's
    // result binds R in `(…) -> R`), falling back to each parameter's erased bound when an argument can't
    // pin it (a raw `findViewById(): T` → `View`), then substitute into the return type.
    val bindings = (methodTypeParamErasure(sym) + inferTypeArguments(sym, call)).toMutableMap()
    applyLowerBounds(sym, call, bindings)
    return (sym.type as? KotlinType)?.let { service.substitute(it, bindings) as? KotlinType }
}

/**
 * The type a constructor call `Foo(args)` produces, with `Foo`'s type parameters inferred from the
 * constructor arguments (`Box("s")` → `Box<String>`), so a chain off it (`Box("s").value`) and the scope
 * function receiver (`Box("s").apply { value }`) resolve the element type. Falls back to the raw type when
 * `Foo` is non-generic, has no matching constructor, or no argument pins a parameter; an unbound parameter
 * (a partial inference) erases to `Any`.
 */
internal fun KotlinResolver.constructorResultType(fqn: String, call: KtCallExpression): KotlinType {
    val tps = service.classTypeParameters(fqn)
    if (tps.isEmpty()) return service.typeByFqn(fqn)
    val paramTypes = service.constructorParamTypes(fqn, call.valueArguments.size) ?: return service.typeByFqn(fqn)
    val bindings = HashMap<String, TypeRef>()
    call.valueArguments.forEachIndexed { i, arg ->
        val pt = paramTypes.getOrNull(i) as? KotlinType ?: return@forEachIndexed
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        if (expr is KtLambdaExpression) return@forEachIndexed // a lambda arg can't pin a scalar type param here
        inferType(expr)?.let { unify(pt, it, bindings) }
    }
    if (bindings.isEmpty()) return service.typeByFqn(fqn)
    return service.typeByFqn(fqn, tps.map { bindings[it] ?: service.typeByFqn("kotlin.Any") })
}

/** `value(args)` where `value` isn't a function — the result of its `invoke` operator (`Greeter()(name)`,
 *  a functional object / DSL). The argument count picks the matching `invoke` overload. Null when the callee
 *  has no inferable type or no applicable `invoke`. */
internal fun KotlinResolver.invokeReturnType(call: KtCallExpression): KotlinType? {
    val calleeType = inferType(call.calleeExpression)?.takeIf { !it.isTypeParameter } ?: return null
    val n = call.valueArguments.size
    val invokes = service.membersNamed(calleeType.qualifiedName, calleeType.typeArguments, "invoke")
        .filter { it.kind == SymbolKind.METHOD }
    return (invokes.firstOrNull { it.paramTypes.size == n } ?: invokes.firstOrNull())?.type as? KotlinType
}

internal fun KotlinResolver.typeOfName(name: String, offset: Int): KotlinType? {
    narrowedType(name)?.let { return it } // the lowerer's explicit flow-narrowing stack
    // Editor smart cast: an `if (x is T)` guard narrows `x` to `T` here (completion + diagnostics both
    // resolve a receiver through inferType -> typeOfName). Off while the lowerer drives the stack above,
    // so the interpreter keeps its own authoritative flow-narrowing.
    if (narrowings.isEmpty()) smartCastTypeAt(name, offset)?.let { return it }
    localsAt(offset).firstOrNull { it.name == name }?.let { return it.type as? KotlinType }
    // Members of any implicit `this` (apply/with/run block, extension fn, enclosing class).
    for (recv in implicitReceiversAt(offset)) memberNamed(recv, name)?.let { return it.type as? KotlinType }
    service.topLevelByName(name).firstOrNull { it.kind == SymbolKind.FIELD }?.let { return it.type as? KotlinType }
    // A bare type name used as an expression (e.g. `Foo` in `Foo.CONST`).
    if (name.firstOrNull()?.isUpperCase() == true) {
        service.resolveTypeName(name, fileContext)?.let { return service.typeByFqn(it) }
    }
    return null
}

internal fun KotlinResolver.typeOfTypeName(expr: KtExpression): KotlinType? {
    val name = (expr as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    if (name.firstOrNull()?.isUpperCase() != true) return null
    return service.resolveTypeName(name, fileContext)?.let { service.typeByFqn(it) }
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

internal fun KotlinResolver.memberNamed(type: KotlinType, name: String): KotlinSymbol? =
    service.membersNamed(type.qualifiedName, type.typeArguments, name).firstOrNull()
