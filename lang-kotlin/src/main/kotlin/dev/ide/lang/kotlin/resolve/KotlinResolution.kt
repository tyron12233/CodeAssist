package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.ImportInfo
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.ValueArgument

/** The Compose calling-convention status of a code position (see [KotlinResolver.composableContextAt]). */
enum class ComposableContext { COMPOSABLE, NON_COMPOSABLE, UNKNOWN }

/** The suspend calling-convention status of a code position (see [KotlinResolver.suspendContextAt]). */
enum class SuspendContext { SUSPEND, NON_SUSPEND, UNKNOWN }

/**
 * Resolution and the inference subset, computed over the LIVE [KtFile] (the buffer being edited).
 * No `BindingContext`: scopes are assembled from PSI parents, names resolved against the scope chain, and
 * a small declared-type-driven typer covers literals, locals-from-initializers, member/call return types,
 * and constructor calls, enough for multi-level member completion (`a.b().c`). Anything it can't type
 * degrades to null, yielding scope-only completion, never an error.
 */
class KotlinResolver(
    private val ktFile: KtFile,
    private val parsed: KotlinParsedFile,
    private val service: KotlinSymbolService,
) {
    val fileContext: FileContext = run {
        val imports = ktFile.importDirectives.mapNotNull { imp ->
            imp.importedFqName?.asString()?.let { ImportInfo(it, imp.aliasName, imp.isAllUnder) }
        }
        FileContext(ktFile.name, ktFile.packageFqName.asString(), imports)
    }

    // --- inference ---

    // Per-snapshot memo caches. Inference + callee resolution are pure for a given (immutable) parse, but
    // recursive and heavily re-entered (esp. on deeply nested Compose, where each call walks its whole ancestor
    // scope chain). Caching turns the O(depth² · calls) blowup into O(calls) — the difference between a ~20s and
    // an instant preview. Safe because a [KotlinResolver] is created per parse snapshot (never reused across edits).
    private val inferCache = HashMap<KtExpression, KotlinType?>()
    private val calleeCache = HashMap<KtCallExpression, KotlinSymbol?>()
    // The call's overload set (all candidates by name+receiver). Resolving it is classpath member/scope lookup;
    // the analyze pass probes it more than once per call (missing-required-arg AND unknown-named-arg checks,
    // plus named-argument completion), so memoize it per snapshot like the callee/infer caches above.
    private val callTargetsCache = HashMap<KtCallExpression, List<KotlinSymbol>>()
    private val implicitReceiversCache = HashMap<Int, List<KotlinType>>()
    // Composable context is identical for every position inside one boundary (a function/accessor/lambda), and
    // it's queried per call expression in the analyze pass, so memoize by that boundary element.
    private val composeCtxCache = HashMap<PsiElement, ComposableContext>()
    private val suspendCtxCache = HashMap<PsiElement, SuspendContext>()

    // Smart-cast narrowings: a stack of `name → narrowed type` scopes the LOWERER pushes while lowering an
    // `if (x is T) { … }` then-branch (or `when (x) { is T -> … }`), so `x`'s members resolve against `T`.
    // [typeOfName] consults it first. Empty during the analyze pass (only the lowerer drives push/pop), so it
    // never affects diagnostics. While a narrowing is active, [inferType]'s cache is bypassed: an expression's
    // type then depends on the flow context, not the expression alone, so a narrowed result must never be
    // cached and served to an unnarrowed query.
    private val narrowings = ArrayDeque<Map<String, KotlinType>>()

    /** Push a smart-cast narrowing scope (`name → type`). The lowerer balances it with [popNarrowing]. */
    fun pushNarrowing(narrowed: Map<String, KotlinType>) = narrowings.addLast(narrowed)

    /** Pop the innermost narrowing scope pushed by [pushNarrowing]. */
    fun popNarrowing() = narrowings.removeLast()

    private fun narrowedType(name: String): KotlinType? {
        for (i in narrowings.indices.reversed()) narrowings[i][name]?.let { return it }
        return null
    }

    fun inferType(expr: KtExpression?): KotlinType? {
        if (expr == null) return null
        // A narrowed type is flow-dependent, not a property of the expression alone — bypass the cache so it
        // can't leak across narrowing scopes.
        val cacheable = narrowings.isEmpty()
        if (cacheable && inferCache.containsKey(expr)) return inferCache[expr]
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
            // `if`/`when` used as an expression: the value is one of the branches, so its type is the type of
            // a branch — the `then`/`else` of an `if`, the first typeable entry of a `when`. (A common Compose
            // pattern: `Icon(if (selected) IconA else IconB, …)` — typing the arg disambiguates the overload.)
            is KtIfExpression -> inferType(branchExpr(expr.then)) ?: inferType(branchExpr(expr.`else`))
            is KtWhenExpression -> expr.entries.firstNotNullOfOrNull { inferType(branchExpr(it.expression)) }
            else -> null
        }
        if (cacheable) inferCache[expr] = r
        return r
    }

    /** Infer the result type of a binary operator: comparison/equality/logical → `Boolean`; elvis → the
     *  non-null left (or the right); arithmetic → numeric promotion for the primitive number types (the builtins
     *  model carries no return type for `Int.times`/etc.), else the operator member's return type, with string
     *  `+` short-circuited to `String` (so `"x" + y` types even when `String.plus` isn't in the member index). */
    private fun inferBinaryType(e: KtBinaryExpression): KotlinType? = when (val token = e.operationToken) {
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
    private fun binaryConventionReturn(e: KtBinaryExpression, name: String): KotlinType? {
        val leftType = inferType(e.left) ?: return null
        val member = service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
            .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
        val callable = member ?: service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name)
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
    private fun inferPrefixType(e: KtPrefixExpression): KotlinType? {
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
    private fun unaryOperator(type: KotlinType, name: String): KotlinType? =
        service.membersNamed(type.qualifiedName, type.typeArguments, name)
            .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.isEmpty() }?.type as? KotlinType

    /** `value as T` / `value as? T` → the target type `T`, nullable for a safe cast (`as?` yields `T?`) or a
     *  written nullable target (`as T?`). Lets `(x as T).member` resolve members against `T`. */
    private fun castType(e: KtBinaryExpressionWithTypeRHS): KotlinType? {
        val raw = e.right?.text?.trim() ?: return null
        val safe = e.operationReference.getReferencedNameElementType() == KtTokens.AS_SAFE
        val nullable = safe || raw.endsWith("?")
        val t = service.typeFromText(raw.removeSuffix("?"), fileContext) ?: return null
        return if (nullable) t.withNullable(true) else t
    }

    /** Kotlin numeric-promotion result type for arithmetic between primitive numbers: the wider operand, with
     *  Byte/Short/Char promoted to `Int`. Null when [left] isn't a primitive number (so a non-numeric operator
     *  overload falls back to its declared return type). An unknown [right] doesn't pull the result below [left]. */
    private fun numericResultType(left: KotlinType, right: KotlinType?): KotlinType? {
        val lr = NUMERIC_RANK[left.qualifiedName] ?: return null
        val rr = right?.qualifiedName?.let { NUMERIC_RANK[it] } ?: -1
        val rank = maxOf(lr, rr).coerceAtLeast(NUMERIC_RANK.getValue("kotlin.Int"))
        return service.typeByFqn(NUMERIC_RANK.entries.first { it.value == rank }.key)
    }

    /** The value of an `if`/`when` branch: a bare expression as-is, or the last statement of a `{ … }` block
     *  branch (so `if (c) { x } else { y }` still types). Null when the branch is absent or not an expression. */
    private fun branchExpr(branch: KtExpression?): KtExpression? = when (branch) {
        is KtBlockExpression -> branch.statements.lastOrNull() as? KtExpression
        else -> branch
    }

    /** `xs[i]` → the element type: the (substituted) return type of the receiver's `get(index)` operator
     *  (`List<String>.get` → `String`, `Map<K,V>.get` → `V?`). Null when the receiver type or `get` is unknown. */
    private fun inferArrayGet(e: KtArrayAccessExpression): KotlinType? {
        val recv = inferType(e.arrayExpression) ?: return null
        val arity = e.indexExpressions.size
        return service.membersNamed(recv.qualifiedName, recv.typeArguments, "get")
            .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.size == arity }
            ?.type as? KotlinType
    }

    /** `this` (optionally `this@Label`) → the matching implicit receiver in scope (innermost when unlabeled). */
    private fun thisType(expr: KtThisExpression): KotlinType? {
        val receivers = implicitReceiversAt(expr.textRange.startOffset)
        val label = expr.getLabelName()
        return if (label == null) receivers.firstOrNull()
        else receivers.firstOrNull { it.qualifiedName.substringAfterLast('.') == label }
    }

    /** `super` (optionally `super<Base>`) → the enclosing class's supertype, so `super.member` lists inherited
     *  members. With no explicit supertype, `kotlin.Any` (whose members `toString`/`hashCode`/… are inherited). */
    private fun superType(expr: KtSuperExpression): KotlinType? {
        expr.superTypeQualifier?.text?.let { service.typeFromText(it, fileContext)?.let { t -> return t } }
        val cls = enclosingClassOrObject(expr.textRange.startOffset) ?: return null
        val superText = cls.superTypeListEntries.firstNotNullOfOrNull { it.typeReference?.text }
        return superText?.let { service.typeFromText(it, fileContext) } ?: service.typeByFqn("kotlin.Any")
    }

    private fun constType(e: KtConstantExpression): KotlinType? {
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
    private fun hexBinType(raw: String): String {
        val radix = if (raw[1].lowercaseChar() == 'x') 16 else 2
        var body = raw.substring(2).replace("_", "")
        val isLong = body.endsWith("L") || body.endsWith("l")
        if (isLong) body = body.dropLast(1)
        if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1)
        val v = body.toLongOrNull(radix) ?: body.toULongOrNull(radix)?.toLong() ?: return "kotlin.Int"
        return if (isLong || v !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "kotlin.Long" else "kotlin.Int"
    }

    private fun typeOfQualified(q: KtQualifiedExpression): KotlinType? {
        // A fully-qualified type used directly (`android.R`, `java.util.Locale`) — resolve the whole text.
        if (service.isKnownType(q.text)) return service.typeByFqn(q.text)
        val receiverType = inferType(q.receiverExpression)
            ?: typeOfTypeName(q.receiverExpression) // receiver may be a class name (companion/static access)
            ?: return null
        return when (val sel = q.selectorExpression) {
            is KtCallExpression -> typeOfCall(sel, receiverType)
            is KtNameReferenceExpression -> {
                val name = sel.getReferencedName()
                // A member property/field, else a NESTED type/object reached through its enclosing type
                // (`Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`) — a classifier, not a member, so it
                // resolves by probing the candidate nested FQN. This makes the receiver of an icon extension
                // property (`Icons.AutoMirrored.Filled.List`) type, so the property binds as an extension.
                memberNamed(receiverType, name)?.type as? KotlinType
                    ?: nestedType(receiverType.qualifiedName, name)
            }
            else -> null
        }
    }

    /** A nested type/object reached as `Owner.Nested` — not a member but a classifier; resolved by probing the
     *  candidate nested FQN (kotlinx-metadata and [KotlinSymbolService.isKnownType] both use the dotted form
     *  `pkg.Outer.Inner`, so the result matches the extension index's receiver key). */
    private fun nestedType(ownerFqn: String, name: String): KotlinType? =
        "$ownerFqn.$name".takeIf { service.isKnownType(it) }?.let { service.typeByFqn(it) }

    private fun typeOfCall(call: KtCallExpression, receiverType: KotlinType?): KotlinType? {
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
     * Apply `T : R` lower bounds (recorded at receiver binding, see [KotlinSymbolService.bindExtensionReceiver])
     * to a callee's still-free return type parameters. `Result<String>.getOrElse { … }`: T : R, T = String ⇒
     * R ≥ String. When the `onFailure` lambda returns a concrete type, argument inference already bound R (e.g.
     * `{ "x" }` → String); but `{ null }` gives R no type, so without this R stays a raw `R`. Here R is widened to
     * its lower bound (String), made nullable when the lambda body is `null` → `String?` (Kotlin's LUB of
     * `String` and `Nothing?`). A `let { null }` has NO such bound, so it is untouched (stays the lambda result).
     */
    private fun applyLowerBounds(sym: KotlinSymbol, call: KtCallExpression, bindings: MutableMap<String, TypeRef>) {
        if (sym.typeParamLowerBounds.isEmpty()) return
        for ((param, lowerRef) in sym.typeParamLowerBounds) {
            val lower = lowerRef as? KotlinType ?: continue
            val cur = bindings[param] as? KotlinType
            when {
                // Argument inference left R free (its lambda returned `null` → no type) → R = the lower bound,
                // nullable when that lambda body is the `null` literal.
                cur == null || cur.isTypeParameter -> bindings[param] = if (returnParamGotNullLambda(sym, call, param)) lower.withNullable(true) else lower
                // R was bound to `Nothing`/`Nothing?` (a `null`/`throw` lambda) → widen to the lower bound.
                cur.qualifiedName == "kotlin.Nothing" -> bindings[param] = lower.withNullable(cur.nullable || lower.nullable)
                else -> {} // a concrete lambda result already pinned R (`getOrElse { "x" }` → String) — keep it
            }
        }
    }

    /** Whether [call] passes a lambda — to the parameter whose functional return type is [param] — whose body is
     *  the `null` literal (so [param] should be nullable). */
    private fun returnParamGotNullLambda(sym: KotlinSymbol, call: KtCallExpression, param: String): Boolean {
        call.valueArguments.forEachIndexed { i, arg ->
            val lambda = arg.getArgumentExpression() as? KtLambdaExpression ?: return@forEachIndexed
            val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
            val ret = service.functionalShape(pt)?.returnType as? KotlinType
            if (ret?.isTypeParameter == true && ret.qualifiedName == param) {
                val last = lambda.bodyExpression?.statements?.lastOrNull() as? KtExpression
                if (last is KtConstantExpression && last.text.trim() == "null") return true
            }
        }
        return false
    }

    /**
     * The type a constructor call `Foo(args)` produces, with `Foo`'s type parameters inferred from the
     * constructor arguments (`Box("s")` → `Box<String>`), so a chain off it (`Box("s").value`) and the scope
     * function receiver (`Box("s").apply { value }`) resolve the element type. Falls back to the raw type when
     * `Foo` is non-generic, has no matching constructor, or no argument pins a parameter; an unbound parameter
     * (a partial inference) erases to `Any`.
     */
    private fun constructorResultType(fqn: String, call: KtCallExpression): KotlinType {
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
    private fun invokeReturnType(call: KtCallExpression): KotlinType? {
        val calleeType = inferType(call.calleeExpression)?.takeIf { !it.isTypeParameter } ?: return null
        val n = call.valueArguments.size
        val invokes = service.membersNamed(calleeType.qualifiedName, calleeType.typeArguments, "invoke")
            .filter { it.kind == SymbolKind.METHOD }
        return (invokes.firstOrNull { it.paramTypes.size == n } ?: invokes.firstOrNull())?.type as? KotlinType
    }

    /** Each of a function's own type parameters mapped to its erased upper bound, so any that argument
     *  inference leaves unbound still resolves to a concrete type rather than a member-less `T`. */
    private fun methodTypeParamErasure(sym: KotlinSymbol): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty() || sym.typeParameterBounds.isEmpty()) return emptyMap()
        val out = HashMap<String, TypeRef>(sym.typeParameters.size)
        sym.typeParameters.forEachIndexed { i, name -> sym.typeParameterBounds.getOrNull(i)?.let { out[name] = it } }
        return out
    }

    /** Resolve a call's callee to the best-fitting function overload (member/extension via its receiver, or
     *  top-level), with receiver type params already bound by [KotlinSymbolService.membersOf]. */
    /** Calls currently being resolved — a re-entrancy guard. The scope-aware branch below consults
     *  [implicitReceiversAt], which resolves enclosing calls' function types via [expectedFunctionTypeFor] →
     *  [resolveCalleeFunction]; without this guard a call whose resolution (transitively) needs its own would
     *  recurse forever (it hangs editor analysis on nested Compose). Re-entry returns null, breaking the cycle. */
    private val resolvingCallees = HashSet<KtCallExpression>()

    private fun resolveCalleeFunction(call: KtCallExpression): KotlinSymbol? {
        if (calleeCache.containsKey(call)) return calleeCache[call]
        if (!resolvingCallees.add(call)) return reentrantCalleeFallback(call) // re-entrant → break the cycle (don't cache)
        val result = try {
            computeCallee(call)
        } finally {
            resolvingCallees.remove(call)
        }
        calleeCache[call] = result
        return result
    }

    /**
     * Re-entrancy fallback for [resolveCalleeFunction]: a callee's resolution is requested WHILE that callee is
     * already mid-resolution (a higher-order call whose lambda-parameter typing loops back into the same call).
     * The cycle must break, but a hard `null` STARVES the lambda of its parameter shape — `it` inside it then
     * goes untyped, losing completion/go-to-def on it. Instead resolve the callee NON-recursively from an
     * UNAMBIGUOUS top-level match by name (+ arity) — skipping the receiver/argument inference that is the cycle.
     * Returns null for a member call (needs the receiver) or an overloaded name, i.e. exactly the prior behaviour
     * in the ambiguous cases — so this only ever ADDS a resolution in the re-entrant window, never changes a
     * confident one. Deliberately NOT cached: the outer (full) resolution still computes and caches the real result.
     */
    private fun reentrantCalleeFallback(call: KtCallExpression): KotlinSymbol? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val argCount = call.valueArguments.size
        val byName = service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
        return byName.singleOrNull { it.paramTypes.size == argCount } ?: byName.singleOrNull()
    }

    private fun computeCallee(call: KtCallExpression): KotlinSymbol? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val argCount = call.valueArguments.size // already includes the trailing lambda
        val q = call.parent as? KtQualifiedExpression
        val receiverType = if (q != null && q.selectorExpression === call) inferType(q.receiverExpression) else null
        val candidates = if (receiverType != null) {
            val members = service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                .filter { it.kind == SymbolKind.METHOD }
            // A member-extension declared in an enclosing scope (`fun Map<…>.printMap()` in this class) resolves
            // on a matching receiver without an import — same seam completion/diagnostics use.
            val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
            members + scopeExts
        } else {
            // Top-level functions (`Text`, `Column`, `remember`, …) resolve via the cheap exact lookup. Only when
            // none matches do we pay for the scope-aware lookup, which also finds a bare-called scope EXTENSION
            // (`itemsIndexed(...)` inside `LazyColumn { }`, on the implicit `LazyListScope`) so its `itemContent`
            // function type + receiver is seen. This keeps the common path off the expensive recursive walk.
            service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
                .ifEmpty { scopeSymbolsAt(call.textRange.startOffset, name).filter { it.name == name && it.kind == SymbolKind.METHOD } }
        }
        // Prefer exact arity; then functions with MORE params than supplied args (defaulted params the caller
        // omits — `Box(modifier){…}` with 4-param Box, `items(list,key){…}` vs all-4-param overloads); then
        // functions with fewer (vararg / extra trailing lambdas). Within each tier, break ties by scoring how
        // well every non-lambda argument (positional AND named) fits each candidate. That picks `items(List<T>…)`
        // over `items(Int…)` when the arg is a `List<Project>`, and the String `TextField(value=…)` overload
        // over the `TextFieldValue` one when `value` is a String.
        val exact = candidates.filter { it.paramTypes.size == argCount }
        if (exact.isNotEmpty()) return bestOverload(exact, call, receiverType)
        val moreParams = candidates.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size > argCount }
        if (moreParams.isNotEmpty()) return bestOverload(moreParams, call, receiverType)
        val fewerParams = candidates.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size < argCount }
        if (fewerParams.isNotEmpty()) return bestOverload(fewerParams, call, receiverType)
        return candidates.firstOrNull()
    }

    /** Pick the best-fitting overload from same-arity-tier [candidates] by [overloadFitScore], breaking a tie by
     *  the most-specific RECEIVER (see [receiverSpecificity]), then in favour of the first (the declaration/lookup
     *  order) so a confident resolution is unchanged. Generalises the old first-positional-argument heuristic to
     *  ALL arguments, positional and NAMED. This matters for the idiomatic Compose call style
     *  `TextField(value = s, onValueChange = { … })`, whose only disambiguating argument (`value`) is named: a
     *  first-positional-only scorer gave up and picked an arbitrary overload, then mistyped the lambda's `it` from
     *  the wrong one. The receiver tiebreak matters for stdlib pairs like `String.removePrefix(): String` vs
     *  `CharSequence.removePrefix(): CharSequence`: both fit a `String` receiver and a `CharSequence` arg equally,
     *  but only the `String` overload's return type lets a further `String`-extension chain (`.removePrefix("").x`)
     *  resolve — without the tiebreak the candidate set's (HashSet) order decided it, intermittently flagging the
     *  chained call unresolved. */
    private fun bestOverload(candidates: List<KotlinSymbol>, call: KtCallExpression, receiverType: KotlinType?): KotlinSymbol {
        if (candidates.size == 1) return candidates.first()
        var best = candidates.first()
        var bestFit = overloadFitScore(best, call)
        var bestSpec = receiverType?.let { receiverSpecificity(best, it) } ?: 0
        for (c in candidates.drop(1)) {
            val fit = overloadFitScore(c, call)
            val spec = receiverType?.let { receiverSpecificity(c, it) } ?: 0
            if (fit > bestFit || (fit == bestFit && spec > bestSpec)) { best = c; bestFit = fit; bestSpec = spec }
        }
        return best
    }

    /** How specifically a candidate applies to a receiver of [receiverType] (higher = more specific) — the
     *  most-specific-receiver tiebreaker among overloads that fit the arguments equally well. A plain member
     *  outranks any extension; among extensions, one declared on the actual type beats one on a supertype
     *  (`String.removePrefix` over `CharSequence.removePrefix`), with a nearer supertype beating a farther one,
     *  mirroring Kotlin's overload resolution. An extension with no/unknown receiver, or one on an unrelated
     *  type, ranks lowest (0). */
    private fun receiverSpecificity(sym: KotlinSymbol, receiverType: KotlinType): Int {
        if (!sym.isExtension) return Int.MAX_VALUE
        val recv = sym.receiverTypeFqn?.let { Builtins.kotlinTypeFor(it) ?: it } ?: return 0
        val actual = Builtins.kotlinTypeFor(receiverType.qualifiedName) ?: receiverType.qualifiedName
        if (recv == actual) return RECEIVER_EXACT
        val idx = service.supertypesOf(actual).indexOfFirst { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == recv }
        return if (idx >= 0) RECEIVER_EXACT - 1 - idx else 0
    }

    /** Score how well [sym] fits [call]'s arguments: +1 for each concrete-typed argument whose inferred type fits
     *  its mapped parameter (exact FQN or a subtype), -1 for each known-type mismatch. Neutral (0), so the scorer
     *  only ever moves a clear winner and never a guess: a type-parameter parameter, a functional parameter slot
     *  (lambda / callable reference / `(…) -> R` / SAM, too imprecise to score), a numeric-to-numeric adaptation,
     *  or an unknown type on either side. Higher means a better fit. */
    private fun overloadFitScore(sym: KotlinSymbol, call: KtCallExpression): Int {
        var score = 0
        call.valueArguments.forEachIndexed { i, arg ->
            if (arg is KtLambdaArgument) return@forEachIndexed
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            if (expr is KtLambdaExpression) return@forEachIndexed // functional arg: shape too imprecise to score
            val pt = sym.paramTypes.getOrNull(argParamIndex(arg, i, sym)) as? KotlinType ?: return@forEachIndexed
            if (pt.isTypeParameter || isFunctionalParam(pt)) return@forEachIndexed // generic / callback slot: skip
            val at = inferType(expr)?.takeIf { !it.isTypeParameter } ?: return@forEachIndexed
            when {
                pt.qualifiedName == at.qualifiedName || pt.isAssignableFrom(at) -> score += 1
                pt.qualifiedName in NUMERIC_RANK && at.qualifiedName in NUMERIC_RANK -> {} // literal numeric coercion
                service.isKnownType(pt.qualifiedName) && service.isKnownType(at.qualifiedName) -> score -= 1
            }
        }
        return score
    }

    /** A function-typed / SAM parameter slot (`onValueChange: (T) -> Unit`, a `@Composable` content lambda, a
     *  callback). Excluded from [overloadFitScore]: the inferred shape of a functional argument vs. the expected
     *  type is too imprecise to disambiguate on, and these slots are never the distinguishing argument anyway. */
    private fun isFunctionalParam(pt: KotlinType): Boolean =
        pt.qualifiedName.startsWith("kotlin.Function") || pt.isExtensionFunctionType || pt.isComposable ||
            service.functionalShape(pt) != null

    /** The declared parameter index a non-lambda value argument fills: a NAMED argument by its name (else its
     *  positional index). The trailing-lambda variant is [lambdaParamIndex]. */
    private fun argParamIndex(arg: ValueArgument, argIndex: Int, sym: KotlinSymbol): Int {
        arg.getArgumentName()?.asName?.identifier?.let { n -> sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it } }
        return argIndex
    }

    private fun inferTypeArguments(sym: KotlinSymbol, call: KtCallExpression): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty()) return emptyMap()
        val bindings = HashMap<String, TypeRef>()
        // valueArguments already includes a trailing lambda (its getArgumentExpression() is the lambda).
        call.valueArguments.forEachIndexed { i, arg ->
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
            if (expr is KtLambdaExpression) bindLambdaReturn(pt, expr, bindings)
            else inferType(expr)?.let { unify(pt, it, bindings) }
        }
        return bindings
    }

    /**
     * The own type parameters of [call]'s callee that cannot be inferred at this site — Kotlin's
     * "Not enough information to infer type variable T" (`mutableStateOf()` with no argument: `T` is observed
     * by the result `MutableState<T>` but nothing supplies it). A type parameter qualifies only when:
     *  - the call carries NO explicit type-argument list (`mutableStateOf<String>()` supplies it),
     *  - it appears in the callee's RETURN type (so the result observes it),
     *  - no value argument binds it (argument inference left it free),
     *  - it is not the extension receiver's own type parameter (a `T.foo()` binds `T` from the receiver), and
     *  - it constrains some value parameter, EVERY one of which received no argument — so no argument could
     *    have bound it. This distinguishes the omitted-argument case from `mutableStateOf(x)` where an
     *    argument is present and inference merely (best-effort) failed.
     * Backs off entirely when a concrete expected type drives the inference (`val s: MutableState<String> =
     * mutableStateOf()`), a spread argument is present, or the callee can't be resolved — conservative, so it
     * never reports a type Kotlin would accept. Shared by the editor diagnostic and the interpreter lowering.
     */
    fun uninferableTypeParameters(call: KtCallExpression): List<String> {
        if (call.typeArgumentList != null) return emptyList()
        if (call.valueArguments.any { it.getSpreadElement() != null }) return emptyList()
        val sym = resolveCalleeFunction(call) ?: return emptyList()
        if (sym.typeParameters.isEmpty()) return emptyList()
        val ret = sym.type as? KotlinType ?: return emptyList()
        // A concrete expected type at this position can drive the inference, so it isn't uninferable.
        (expectedTypeAt(call.textRange.startOffset) as? KotlinType)?.let { exp ->
            if (!exp.isTypeParameter && service.isKnownType(exp.qualifiedName)) return emptyList()
        }
        val bound = inferTypeArguments(sym, call).keys
        val supplied = suppliedValueParameterIndices(sym, call)
        return sym.typeParameters.filter { tp ->
            if (tp in bound || tp == sym.receiverTypeParam) return@filter false
            if (!mentionsTypeParam(ret, tp)) return@filter false
            val mentioning = sym.paramTypes.indices.filter { mentionsTypeParam(sym.paramTypes[it], tp) }
            mentioning.isNotEmpty() && mentioning.none { it in supplied }
        }
    }

    /**
     * The delegate-accessor operators a `by`-delegated [property] needs but that are NOT in scope: `getValue`
     * always, plus `setValue` for a `var`. Kotlin desugars `val x by d` to `d.getValue(thisRef, prop)` and a
     * `var` write to `d.setValue(…)`, requiring an applicable `operator fun` on the delegate's type. For
     * Compose's `MutableState` these are EXTENSIONS in `androidx.compose.runtime`, so
     * `val text by remember { mutableStateOf(0) }` does NOT compile without `import
     * androidx.compose.runtime.getValue` — exactly the reported gap. A plain MEMBER operator, or an in-scope
     * (imported / same-package / default-imported, e.g. `kotlin.Lazy.getValue`) extension operator on the
     * delegate type or a supertype, satisfies it. Empty when the delegate type is unknown OR the operator
     * isn't modeled at all on the classpath (conservative — never invents a rejection).
     */
    fun missingDelegateOperators(property: KtProperty): List<String> {
        val delegate = property.delegateExpression ?: return emptyList()
        val dt = inferType(delegate) ?: return emptyList()
        if (dt.isTypeParameter) return emptyList()
        // When the delegate's type can't be inferred (`remember { mutableStateOf() }`), the root error is the
        // inference failure, not the operator — its type (an erased `MutableState<Any?>`) is fictitious, so a
        // getValue check against it is misleading. Defer to the `cannotInferType` diagnostic.
        if (delegateContainsUninferableCall(delegate)) return emptyList()
        val needed = if (property.isVar) listOf("getValue", "setValue") else listOf("getValue")
        return needed.filter { op -> !delegateOperatorInScope(dt, op) }
    }

    /**
     * Fully-qualified names to import to bring a `by`-delegate's missing `getValue`/`setValue` operator into
     * scope — the quick-fix for `val text by remember { mutableStateOf(0) }` →
     * `import androidx.compose.runtime.getValue`. Only out-of-scope EXTENSION operators that actually apply to
     * the delegate's type are offered (so an unrelated `getValue` isn't suggested); empty when the operator is
     * already in scope, unmodeled, or the delegate type is unknown/uninferable.
     */
    fun delegateOperatorImportCandidates(property: KtProperty): List<String> {
        val delegate = property.delegateExpression ?: return emptyList()
        val dt = inferType(delegate)?.takeIf { !it.isTypeParameter } ?: return emptyList()
        if (delegateContainsUninferableCall(delegate)) return emptyList()
        val ops = if (property.isVar) listOf("getValue", "setValue") else listOf("getValue")
        val out = LinkedHashSet<String>()
        for (op in ops) {
            val candidates = service.membersNamed(dt.qualifiedName, dt.typeArguments, op)
                .filter { it.kind == SymbolKind.METHOD }
            if (candidates.any { !it.isExtension || extensionInScope(it) }) continue // already satisfied
            candidates.filter { it.isExtension && !extensionInScope(it) }.forEach { ext ->
                val pkg = ext.packageName ?: ext.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null }
                if (pkg != null) out += "$pkg.$op"
            }
        }
        return out.toList()
    }

    /** Whether the delegate expression contains a call whose type arguments can't be inferred — so the
     *  delegate-operator check should stand down in favour of the type-inference diagnostic. */
    private fun delegateContainsUninferableCall(delegate: KtExpression): Boolean {
        var found = false
        fun rec(e: PsiElement) {
            if (found) return
            if (e is KtCallExpression && uninferableTypeParameters(e).isNotEmpty()) { found = true; return }
            var c = e.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        rec(delegate)
        return found
    }

    /** Whether a `getValue`/`setValue` operator named [op] is available for a delegate of type [delegateType]:
     *  a plain member, or an in-scope extension. Returns true (don't flag) when none is modeled at all. */
    private fun delegateOperatorInScope(delegateType: KotlinType, op: String): Boolean {
        val candidates = service.membersNamed(delegateType.qualifiedName, delegateType.typeArguments, op)
            .filter { it.kind == SymbolKind.METHOD }
        if (candidates.isEmpty()) return true // operator not modeled on the classpath → conservative
        return candidates.any { !it.isExtension || extensionInScope(it) }
    }

    /** Whether the extension [sym] is in scope here — imported (explicit/star), same-package, or
     *  default-imported. No package info → don't guess a rejection. Mirrors `KotlinSourceAnalyzer.extensionInScope`. */
    private fun extensionInScope(sym: KotlinSymbol): Boolean {
        val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
        if (pkg == fileContext.packageName || dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(pkg)) return true
        return fileContext.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
    }

    /** Whether the type reference [t] is, or nests, the type parameter named [name]. */
    private fun mentionsTypeParam(t: TypeRef?, name: String): Boolean {
        if (t == null) return false
        if (t is KotlinType && t.isTypeParameter && t.qualifiedName == name) return true
        return t.typeArguments.any { mentionsTypeParam(it, name) }
    }

    /** The declared value-parameter indices that received an argument at [call]: a named argument by its
     *  parameter name, a trailing lambda by Kotlin's trailing-lambda rule (the last parameter), the rest by
     *  position. A named argument whose name matches no parameter contributes nothing. */
    private fun suppliedValueParameterIndices(sym: KotlinSymbol, call: KtCallExpression): Set<Int> {
        val paramCount = maxOf(sym.paramTypes.size, sym.paramNames.size)
        val out = HashSet<Int>()
        call.valueArguments.forEachIndexed { i, arg ->
            val named = arg.getArgumentName()?.asName?.identifier
            val idx = when {
                named != null -> sym.paramNames.indexOf(named).takeIf { it >= 0 } ?: return@forEachIndexed
                arg is KtLambdaArgument -> (paramCount - 1).coerceAtLeast(0)
                else -> i
            }
            out += idx
        }
        return out
    }

    /**
     * The name (or `#index`) of a value parameter the [call] leaves unfilled even though it is REQUIRED — no
     * default, not a vararg — i.e. Kotlin's "No value passed for parameter 'x'" (`Button { }` omits the
     * required `onClick`). Null when the call is valid OR can't be judged soundly. Conservative over the
     * parse-only model, mirroring the other call checks:
     *  - backs off entirely if ANY candidate overload's per-parameter defaults are UNKNOWN (Java bytecode, an
     *    old cache) — that overload might accept the call;
     *  - backs off on a spread argument (arity opaque);
     *  - reports only when EVERY candidate is missing a required parameter (so a sibling overload that accepts
     *    the args makes it valid — `remember { }` vs `remember(vararg, calc)`).
     * Used by both the editor diagnostic (`kt.argumentCount`) and the Compose preview lowering (which rejects
     * the call rather than running invalid code with a null stand-in for the missing argument).
     */
    fun missingRequiredArgument(call: KtCallExpression): String? {
        if (call.valueArguments.any { it.getSpreadElement() != null }) return null
        val candidates = runCatching { callTargets(call) }.getOrDefault(emptyList())
            .filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
        if (candidates.isEmpty()) return null
        // Every candidate must carry KNOWN per-parameter defaults (size matches its params); else an overload we
        // can't judge might accept the call, so we must not flag.
        if (candidates.any { it.paramTypes.isNotEmpty() && it.paramHasDefault.size != it.paramTypes.size }) return null
        var report: String? = null
        for (c in candidates) {
            val supplied = suppliedValueParameterIndices(c, call)
            val missing = c.paramTypes.indices.firstOrNull { i ->
                i !in supplied && i != c.varargParamIndex && c.paramHasDefault.getOrElse(i) { true } == false
            } ?: return null // this overload is fully satisfied → the call is valid
            if (report == null) {
                val nm = c.paramNames.getOrNull(missing)
                report = if (nm != null && nm.isNotEmpty()) "'$nm'" else "#${missing + 1}"
            }
        }
        return report
    }

    /** A lambda fills a functional parameter (a Kotlin `(…) -> R` or a Java SAM); bind its result type
     *  parameter from the lambda's inferred result (`map { … }`'s `R`, `let`'s `R`). */
    private fun bindLambdaReturn(pt: KotlinType, lambda: KtLambdaExpression, bindings: MutableMap<String, TypeRef>) {
        val r = service.functionalShape(pt)?.returnType as? KotlinType ?: return
        if (r.isTypeParameter) inferLambdaResult(lambda)?.let { bindings.putIfAbsent(r.qualifiedName, it) }
    }

    private fun inferLambdaResult(lambda: KtLambdaExpression): TypeRef? =
        inferType(lambda.bodyExpression?.statements?.lastOrNull() as? KtExpression)

    /** The `(P…) -> R` type a lambda is expected to be (from the parameter it fills), receiver-bound — used
     *  to type the lambda's `it`/named parameters. */
    /** When [lambda] fills an EXTENSION-function-typed parameter (`RowScope.() -> Unit`), the receiver type its
     *  body has as an implicit `this` (the Compose-scope content-lambda case); null for a plain lambda. */
    fun lambdaReceiverType(lambda: KtLambdaExpression): KotlinType? =
        expectedFunctionTypeFor(lambda)?.takeIf { it.isExtensionFunctionType }?.typeArguments?.firstOrNull() as? KotlinType

    private fun expectedFunctionTypeFor(lambda: KtLambdaExpression): KotlinType? {
        val (call, argIndex) = enclosingCallAndParamIndex(lambda) ?: return null
        val sym = resolveCalleeFunction(call) ?: return null
        val raw = sym.paramTypes.getOrNull(lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return null
        if (!TypeRendering.isFunctionType(raw.qualifiedName)) return null
        // Bind the function's type params from the NON-lambda value args (with(x){…} binds T from x), so the
        // block's receiver/params are concrete. (Skip lambdas to avoid recursing back into this lambda.)
        return service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType
    }

    /** The value-parameter types a lambda receives (in order), from the functional parameter it fills — for
     *  inlay hints on `{ x -> … }` / the implicit `it`. Handles both a Kotlin function-type parameter and a
     *  Java SAM (`stream().map { it }`). Empty when the expected type is unknown. */
    fun lambdaParameterTypes(lambda: KtLambdaExpression): List<TypeRef?> =
        expectedLambdaShape(lambda)?.parameterTypes ?: emptyList()

    /** The functional shape (value-param types + result) a lambda is expected to satisfy — its parameter slot
     *  in the enclosing call, resolved to a Kotlin function type or a Java SAM, with the call's non-lambda
     *  arguments already used to bind the function's type parameters. */
    private fun expectedLambdaShape(lambda: KtLambdaExpression): KotlinSymbolService.FunctionalShape? {
        val (call, argIndex) = enclosingCallAndParamIndex(lambda) ?: return null
        val sym = resolveCalleeFunction(call) ?: return null
        val raw = sym.paramTypes.getOrNull(lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return null
        val bound = service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType ?: return null
        return service.functionalShape(bound)
    }

    private fun bindingsFromValueArgs(sym: KotlinSymbol, call: KtCallExpression): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty()) return emptyMap()
        val bindings = HashMap<String, TypeRef>()
        val needed = sym.typeParameters.toHashSet()
        call.valueArguments.forEachIndexed { i, arg ->
            // `unify` is putIfAbsent (first binding wins), so once every type parameter is bound the remaining
            // arguments can only no-op — stop inferring siblings (a nested Compose tree fans this out hard).
            // Behaviour-identical: a key-form mismatch just means no early exit, never a different result.
            if (bindings.keys.containsAll(needed)) return bindings
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            if (expr is KtLambdaExpression) return@forEachIndexed
            val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
            inferType(expr)?.let { unify(pt, it, bindings) }
        }
        return bindings
    }

    /**
     * The chain of implicit `this` receivers in scope at [offset] (innermost first): the receiver of each
     * enclosing receiver-lambda (`apply`/`with`/`run`/DSL builders — `T.() -> R`), the enclosing extension
     * function's receiver, and the enclosing class. Their members are visible without an explicit receiver.
     */
    fun implicitReceiversAt(offset: Int): List<KotlinType> =
        implicitReceiversCache.getOrPut(offset) { computeImplicitReceiversAt(offset) }

    private fun computeImplicitReceiversAt(offset: Int): List<KotlinType> {
        val out = ArrayList<KotlinType>()
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtLambdaExpression -> expectedFunctionTypeFor(node)
                    ?.takeIf { it.isExtensionFunctionType }
                    ?.let { (it.typeArguments.firstOrNull() as? KotlinType)?.let(out::add) }
                is KtNamedFunction -> node.receiverTypeReference?.text
                    ?.let { service.typeFromText(it, fileContext) }?.let(out::add)
                is KtClassOrObject -> node.fqName?.asString()?.let { out += service.typeByFqn(it) }
                else -> {}
            }
            node = node.parent
        }
        return out
    }

    /** The call a lambda argument belongs to + its parameter index. `KtCallExpression.valueArguments`
     *  ALREADY includes the trailing lambda, so the index is its position in that list. */
    private fun enclosingCallAndParamIndex(lambda: KtLambdaExpression): Pair<KtCallExpression, Int>? {
        val valueArg = lambda.parent as? ValueArgument ?: return null
        val call = when (val p = lambda.parent) {
            is KtLambdaArgument -> p.parent as? KtCallExpression
            is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        } ?: return null
        return call to call.valueArguments.indexOf(valueArg).coerceAtLeast(0)
    }

    /** The declared parameter index a lambda value-argument at [argIndex] fills: a named lambda by its name, a
     *  trailing lambda by Kotlin's trailing-lambda rule (the LAST parameter — so a defaulted leading param like
     *  `modifier` doesn't misalign `Column { }`/`LazyColumn { }`'s content receiver), else its positional index. */
    private fun lambdaParamIndex(call: KtCallExpression, argIndex: Int, sym: KotlinSymbol): Int {
        val arg = call.valueArguments.getOrNull(argIndex)
        arg?.getArgumentName()?.asName?.identifier?.let { n -> sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it } }
        val paramCount = maxOf(sym.paramTypes.size, sym.paramNames.size)
        if (arg is KtLambdaArgument) return (paramCount - 1).coerceAtLeast(0)
        return argIndex
    }

    /** The function a [call] resolves to (the single best overload by arity), exposed for callers that need to
     *  inspect the callee — e.g. the Compose calling-convention check (is the callee `@Composable`?). */
    fun calleeFunctionOf(call: KtCallExpression): KotlinSymbol? = resolveCalleeFunction(call)

    /** The infix function a binary `left <name> right` resolves to — a custom `infix fun`, `to`, `until`, … : a
     *  single-param member of the left type (member-first), else a single-param extension. Null for an
     *  operator-token binary (`+`, `<`) or when unresolved. Exposed for semantic highlighting of an infix call. */
    fun resolveInfixFunction(e: KtBinaryExpression): KotlinSymbol? {
        if (e.operationToken != KtTokens.IDENTIFIER) return null
        val name = e.operationReference.getReferencedName()
        val leftType = inferType(e.left) ?: return null
        return service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
            .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
            ?: service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name)
                .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
    }

    /**
     * Whether the calling context at [offset] is a `@Composable` context, per Compose's calling convention
     * (the rule the Compose compiler's `ComposableCallChecker` enforces):
     *  - a `@Composable` function body (or a `@Composable` property accessor) → [ComposableContext.COMPOSABLE];
     *  - a lambda whose expected type is `@Composable` (a content slot like `setContent`/`Column`) → COMPOSABLE;
     *  - a plain (non-inline) lambda is its own non-composable boundary → [ComposableContext.NON_COMPOSABLE];
     *  - an `inline` lambda (`repeat`/`with`/`forEach`/`let`…) is transparent — composability flows through it
     *    from the enclosing scope, so the walk continues outward;
     *  - the file/top level → NON_COMPOSABLE.
     * [ComposableContext.UNKNOWN] when a lambda's expected type AND callee both fail to resolve (the parse-only
     * model can't tell) — callers should back off (no diagnostic, no completion boost) to avoid false positives.
     */
    fun composableContextAt(offset: Int): ComposableContext {
        // The walk is decided entirely by the boundary nodes (function/accessor/lambda) above the offset, so the
        // nearest such boundary is a sound cache key — every position within it resolves to the same context.
        val boundary = run {
            var n: PsiElement? = elementAt(offset)
            while (n != null && n !is KtNamedFunction && n !is org.jetbrains.kotlin.psi.KtPropertyAccessor && n !is KtLambdaExpression) n = n.parent
            n
        }
        boundary?.let { composeCtxCache[it]?.let { hit -> return hit } }
        return composableContextWalk(offset).also { if (boundary != null) composeCtxCache[boundary] = it }
    }

    private fun composableContextWalk(offset: Int): ComposableContext {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtNamedFunction ->
                    return if (node.hasComposableAnnotation()) ComposableContext.COMPOSABLE else ComposableContext.NON_COMPOSABLE
                is org.jetbrains.kotlin.psi.KtPropertyAccessor ->
                    if (node.hasComposableAnnotation()) return ComposableContext.COMPOSABLE
                is KtLambdaExpression -> {
                    val expected = expectedFunctionTypeFor(node)
                    if (expected?.isComposable == true) return ComposableContext.COMPOSABLE
                    val callee = enclosingCallAndParamIndex(node)?.let { resolveCalleeFunction(it.first) }
                    // Couldn't determine what kind of lambda this is → unknown context (back off downstream).
                    if (expected == null && callee == null) return ComposableContext.UNKNOWN
                    // A non-inline lambda resets the context; an inline lambda is transparent (keep walking out).
                    if (callee?.isInline != true) return ComposableContext.NON_COMPOSABLE
                }
                else -> {}
            }
            node = node.parent
        }
        return ComposableContext.NON_COMPOSABLE
    }

    private fun org.jetbrains.kotlin.psi.KtAnnotated.hasComposableAnnotation(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Composable" }

    /**
     * Whether the calling context at [offset] is a `suspend` context, per Kotlin's coroutine calling convention
     * (the rule the compiler's coroutine checker enforces: a suspend function may be called only from another
     * suspend function or a suspend lambda):
     *  - a `suspend` function body → [SuspendContext.SUSPEND]; a plain function / property accessor body → NON_SUSPEND;
     *  - a lambda whose expected type is a `suspend (…) -> R` (`kotlin.SuspendFunctionN`) → SUSPEND;
     *  - an `inline` lambda (`repeat`/`with`/`forEach`/`let`/`coroutineScope`…) is transparent: suspend-ness
     *    flows through it from the enclosing scope, so the walk continues outward;
     *  - a non-inline lambda filling a SOURCE callee's plainly-non-suspend functional parameter → NON_SUSPEND;
     *  - the file/top level → NON_SUSPEND.
     * [SuspendContext.UNKNOWN] (callers back off) when a lambda's suspend-ness can't be trusted. Binary suspend
     * parameters ARE normally recovered: the metadata decoder rewrites a `suspend (…) -> R` (stored JVM-lowered as
     * a continuation-expanded `FunctionN` with the `isSuspend` flag) back to `kotlin.SuspendFunctionN`, so a freshly
     * decoded `launch`/`withContext`/`flow { }` lambda is detected as SUSPEND above. But a STALE persistent-cache
     * entry written before that rewrite still carries the lowered `FunctionN`, so a non-inline lambda filling a
     * BINARY callee's functional parameter that is NOT a `SuspendFunctionN` could still secretly be a suspend
     * lambda; reporting NON_SUSPEND there would false-positive on the most common coroutine pattern. Hence only a
     * SOURCE callee's function-type FQN is trusted for NON_SUSPEND; an unconfirmed binary one stays UNKNOWN.
     */
    fun suspendContextAt(offset: Int): SuspendContext {
        // The nearest enclosing function/accessor/lambda fully decides the context (every position within it
        // resolves the same), so it is a sound cache key (mirrors [composableContextAt]).
        val boundary = run {
            var n: PsiElement? = elementAt(offset)
            while (n != null && n !is KtNamedFunction && n !is org.jetbrains.kotlin.psi.KtPropertyAccessor && n !is KtLambdaExpression) n = n.parent
            n
        }
        boundary?.let { suspendCtxCache[it]?.let { hit -> return hit } }
        return suspendContextWalk(offset).also { if (boundary != null) suspendCtxCache[boundary] = it }
    }

    private fun suspendContextWalk(offset: Int): SuspendContext {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtNamedFunction ->
                    return if (node.hasModifier(KtTokens.SUSPEND_KEYWORD)) SuspendContext.SUSPEND else SuspendContext.NON_SUSPEND
                // Property accessors (and field initializers) are never suspend; they're a non-suspend boundary.
                is org.jetbrains.kotlin.psi.KtPropertyAccessor -> return SuspendContext.NON_SUSPEND
                is KtLambdaExpression -> {
                    val expected = expectedFunctionTypeFor(node)
                    if (expected != null && TypeRendering.isSuspendFunctionType(expected.qualifiedName)) return SuspendContext.SUSPEND
                    val callee = enclosingCallAndParamIndex(node)?.let { resolveCalleeFunction(it.first) }
                    when {
                        // An inline lambda is transparent: keep walking out to the real enclosing boundary.
                        callee?.isInline == true -> {}
                        // A SOURCE callee's functional-parameter FQN is faithful: a non-`SuspendFunctionN` type is
                        // genuinely a plain lambda, so this lambda is its own non-suspend boundary.
                        callee != null && callee.origin.fromSource && expected != null -> return SuspendContext.NON_SUSPEND
                        // Binary callee (suspend marker may be lost) or unresolved → can't tell; back off.
                        else -> return SuspendContext.UNKNOWN
                    }
                }
                else -> {}
            }
            node = node.parent
        }
        return SuspendContext.NON_SUSPEND
    }

    private fun unify(param: KotlinType, arg: KotlinType, bindings: MutableMap<String, TypeRef>) {
        if (param.isTypeParameter) { bindings.putIfAbsent(param.qualifiedName, arg); return }
        // A vararg parameter (`of(E...)`) is an `Array<E>`; a single scalar argument unifies with the element.
        if (param.qualifiedName == "kotlin.Array" && param.typeArguments.size == 1 &&
            arg.qualifiedName != "kotlin.Array"
        ) {
            (param.typeArguments.first() as? KotlinType)?.let { unify(it, arg, bindings) }
            return
        }
        param.typeArguments.zip(arg.typeArguments).forEach { (p, a) ->
            if (p is KotlinType && a is KotlinType) unify(p, a, bindings)
        }
    }

    private fun typeOfName(name: String, offset: Int): KotlinType? {
        narrowedType(name)?.let { return it } // an active smart-cast (`if (x is T)`) overrides the declared type
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

    private fun typeOfTypeName(expr: KtExpression): KotlinType? {
        val name = (expr as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (name.firstOrNull()?.isUpperCase() != true) return null
        return service.resolveTypeName(name, fileContext)?.let { service.typeByFqn(it) }
    }

    /** `X::class` → `kotlin.reflect.KClass<X>`. The left-hand side is either a classifier name (`Main::class`,
     *  `Foo.Bar::class`, `String::class`) or a value expression (`instance::class`); either way its type is the
     *  literal's single type argument. So `Main::class.java` resolves the `KClass<T>.java` extension to
     *  `Class<Main>`, and `Main::class.simpleName` reaches a `KClass` member. The argument is made non-null
     *  (`KClass`'s parameter is `T : Any`); a bare/reified type-parameter LHS leaves it open (raw `KClass`). */
    private fun classLiteralType(e: KtClassLiteralExpression): KotlinType {
        val lhs = e.receiverExpression
        val arg = lhs?.let { inferType(it) ?: typeOfTypeName(it) }?.withNullable(false)
        return service.typeByFqn("kotlin.reflect.KClass", listOfNotNull(arg))
    }

    private fun memberNamed(type: KotlinType, name: String): KotlinSymbol? =
        service.membersNamed(type.qualifiedName, type.typeArguments, name).firstOrNull()

    /** The member named [name] on the nearest implicit receiver in scope at [offset] — the `this` of an
     *  `apply`/`with`/`run` block, an enclosing extension function's receiver, or the enclosing class — for
     *  highlighting a bare member read (`p.apply { x }`). Innermost receiver first; null when none has it. */
    fun implicitReceiverMember(name: String, offset: Int): KotlinSymbol? {
        if (name.isEmpty()) return null
        for (recv in implicitReceiversAt(offset)) memberNamed(recv, name)?.let { return it }
        return null
    }

    /** The simple names of [typeFqn]'s enum constants — for highlighting `Color.RED` as an enum constant. */
    fun enumConstantNames(typeFqn: String): Set<String> = service.enumConstantsOf(typeFqn).mapTo(HashSet()) { it.name }

    /** A static/companion member named [name] on type [typeFqn] (`MaterialTheme.colorScheme`, `Type.CONST`),
     *  for member-read highlighting. */
    fun staticMemberNamed(typeFqn: String, name: String): KotlinSymbol? =
        service.companionMembersFor(typeFqn, name).firstOrNull { it.name == name }
            ?: service.membersNamed(typeFqn, emptyList(), name).firstOrNull()

    /** An instance member named [name] on [receiverType] (`obj.prop`), for member-read highlighting. */
    fun instanceMemberNamed(receiverType: KotlinType, name: String): KotlinSymbol? =
        service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name).firstOrNull()

    // --- scopes ---

    /** Locals + parameters in scope at [offset] (declared before it). */
    fun localsAt(offset: Int): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.filter { it.textRange.endOffset <= offset }.forEach { st ->
                    when (st) {
                        is KtProperty -> out += localVar(st)
                        is KtDestructuringDeclaration -> out += destructuringLocals(st, inferType(st.initializer))
                        is KtNamedFunction -> out += KotlinSymbol(
                            st.name ?: "_", SymbolKind.METHOD,
                            type = service.typeFromText(st.typeReference?.text, fileContext), origin = SOURCE,
                            declarationNode = runCatching { parsed.adapt(st) }.getOrNull(),
                        )
                        else -> {}
                    }
                }
                // A lambda's value parameters are handled by the KtLambdaExpression branch below (which types
                // `it`/named params from the functional parameter the lambda fills). Skip the KtFunctionLiteral
                // here — it IS a KtFunction, and adding its params via param() (type-from-text only) would
                // shadow the typed copy with an untyped one (`{ inner -> }`'s `inner` becomes null-typed first).
                is KtFunctionLiteral -> {}
                is KtFunction -> node.valueParameters.forEach { out += param(it) }
                is KtForExpression -> node.loopParameter?.let { lp ->
                    // The loop variable is in scope only in the BODY (not the `in` clause). Gating on that both
                    // matches Kotlin scoping AND breaks a recursion: inferring the loop range's type queries
                    // locals at the range's own position (in the `in` clause), which then must NOT re-enter this
                    // branch — else `for (x in m)` would infer `m` → look up locals → re-infer `m` forever.
                    val body = node.body
                    if (body != null && offset >= body.textRange.startOffset) {
                        // The loop variable's type is the iteration element type (`for (x in list)` → x is the
                        // element); a destructuring loop var (`for ((k, v) in map)`) splits it by componentN.
                        val element = iterationElementType(inferType(node.loopRange))
                        val dd = lp.destructuringDeclaration
                        if (dd != null) out += destructuringLocals(dd, element)
                        else out += loopParam(lp, element)
                    }
                }
                is KtCatchClause -> node.catchParameter?.let { out += param(it) }
                is KtWhenExpression -> node.subjectVariable?.let { out += localVar(it) }
                is KtLambdaExpression -> {
                    // Type the lambda's `it` / named params from the functional parameter it fills, so
                    // completion inside `"".let { it.<caret> }` knows `it` is String and `stream().map { it }`
                    // knows `it` is the element type. A RECEIVER lambda (`T.() -> R`) has its receiver dropped
                    // (an implicit `this`, handled by implicitReceiversAt), so it contributes no value param.
                    val inputs = expectedLambdaShape(node)?.parameterTypes.orEmpty()
                    val params = node.valueParameters
                    if (params.isEmpty()) {
                        // The implicit `it` exists only for a single-value-parameter functional type.
                        if (inputs.size == 1) {
                            out += KotlinSymbol("it", SymbolKind.PARAMETER, type = inputs.first(), origin = SOURCE)
                        }
                    } else {
                        params.forEachIndexed { i, p ->
                            val t = service.typeFromText(p.typeReference?.text, fileContext) ?: inputs.getOrNull(i)
                            // A destructuring lambda parameter (`forEach { (k, v) -> }`) binds its entries by
                            // componentN of the parameter's type (the Map.Entry / Pair / data class it receives),
                            // not a single `_`.
                            val dd = p.destructuringDeclaration
                            if (dd != null) {
                                out += destructuringLocals(dd, t as? KotlinType)
                            } else {
                                out += KotlinSymbol(
                                    p.name ?: "_", SymbolKind.PARAMETER, type = t, origin = SOURCE,
                                    declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
            node = node.parent
        }
        return out
    }

    /**
     * Whether the receiver [expr] is a type/static reference (`String.`, `Locale.`, a qualified type)
     * rather than a value/instance (`listOf("").`, `someVar.`, a call, a literal). Drives instance-vs-static
     * member filtering at the `.`: a value is shadowing if a local/param of that name is in scope.
     */
    fun isTypeReceiver(expr: KtExpression): Boolean = typeDenotationFqn(expr) != null

    /**
     * If [expr] denotes a TYPE / static reference (`String`, `java.util.Locale`, the Android `R.layout`, an
     * `Outer.Inner`) rather than a value/instance, the resolved FQN; else null. A simple capitalized name
     * resolves through imports/classpath (unless a local of that name shadows it); a qualified expression
     * resolves either as a fully-qualified type by its own text or as a nested type through a resolved outer
     * — so `R.layout.<caret>` (where `layout` is lower-case) is still recognized as static navigation.
     */
    fun typeDenotationFqn(expr: KtExpression): String? = when (expr) {
        is KtParenthesizedExpression -> expr.expression?.let { typeDenotationFqn(it) }
        is KtNameReferenceExpression -> {
            val name = expr.getReferencedName()
            when {
                name.firstOrNull()?.isUpperCase() != true -> null // type names are capitalized
                localsAt(expr.textRange.startOffset).any { it.name == name } -> null // a value shadows the type
                // An `object` singleton (`CardDefaults`, `MaterialTheme`) is an INSTANCE, not a type — its
                // members are reached like an instance's, so it is NOT a type/static receiver.
                else -> service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) && !service.isObject(it) }
            }
        }
        is KtQualifiedExpression -> {
            val sel = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            when {
                sel == null -> null
                // (a) fully-qualified type by its own text: `java.util.Locale` (but not a qualified `object`)
                sel.firstOrNull()?.isUpperCase() == true && service.isKnownType(expr.text) && !service.isObject(expr.text) -> expr.text
                // (b) nested type through a resolved outer: `R.layout`, `Outer.Inner`
                else -> typeDenotationFqn(expr.receiverExpression)?.let { "$it.$sel".takeIf { f -> service.isKnownType(f) } }
            }
        }
        else -> null // calls, literals, `this`, `super` → instances
    }

    /** Whether [name] is a type parameter declared by an enclosing function / class / property at [offset]
     *  (`fun <T>`, `class C<T>`, `val <T> List<T>.x`) — so a generic type reference isn't flagged unresolved. */
    fun isTypeParameterInScope(name: String, offset: Int): Boolean {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is org.jetbrains.kotlin.psi.KtTypeParameterListOwner && node.typeParameters.any { it.name == name }) return true
            node = node.parent
        }
        return false
    }

    private fun enclosingClassOrObject(offset: Int): KtClassOrObject? {
        var node: PsiElement? = elementAt(offset)
        while (node != null) { if (node is KtClassOrObject) return node; node = node.parent }
        return null
    }

    /** Whether a bare [name] resolves to anything in scope (local, implicit-receiver member, top-level,
     *  import, or a known type). Used by the unresolved-reference diagnostic. */
    fun bareNameResolves(name: String, offset: Int): Boolean {
        if (localsAt(offset).any { it.name == name }) return true
        // A reified type parameter used in expression position (`T::class`) is in scope, not unresolved.
        if (isTypeParameterInScope(name, offset)) return true
        // Top-level declarations in THIS live file (the module index is disk-based and may lag the buffer):
        // functions/properties AND classes/objects/typealiases (a same-file `object Foo` / `class Foo` is a
        // resolvable bare reference — `Foo()` / `Foo.bar` — before the index has caught up to the buffer).
        if (ktFile.declarations.any { it is org.jetbrains.kotlin.psi.KtNamedDeclaration && it.name == name }) return true
        // Members of an ENCLOSING class of the live buffer — the symbol service indexes disk, not the file
        // being edited, so a same-file member (`field`, `helper()`) won't appear in membersOf() below.
        if (enclosingClassMembersContain(offset, name)) return true
        if (implicitReceiversAt(offset).any { recv ->
                service.membersNamed(recv.qualifiedName, recv.typeArguments, name).isNotEmpty()
            }
        ) return true
        // A top-level callable (`remember`, `mutableStateOf`) resolves bare only when it is actually in
        // scope — explicitly imported, star-imported, same-package, or default-imported. A classpath
        // callable that was never imported stays unresolved, as Kotlin reports (mirrors the extension rule).
        if (service.topLevelByName(name).any { topLevelInScope(it, fileContext) }) return true
        if (fileContext.imports.any { !it.isStar && it.simpleName == name }) return true
        return service.resolveTypeName(name, fileContext)?.let { service.isKnownType(it) } == true
    }

    /**
     * Whether a top-level callable [sym] is in scope at the use site: Kotlin resolves a top-level
     * function/property only when its package is imported (explicitly or via a star/default import) or is the
     * file's own package. No package info → don't guess a rejection (treat as in scope). Mirrors the
     * extension-visibility rule in [KotlinSourceAnalyzer.extensionInScope].
     */
    private fun topLevelInScope(sym: KotlinSymbol, ctx: FileContext): Boolean {
        val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
        if (pkg == ctx.packageName || dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(pkg)) return true
        return ctx.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
    }

    /** Whether [name] is a member (function/property or a constructor `val/var` param) of any class enclosing
     *  [offset] in the live buffer. Resolves same-file members the disk-based symbol service can't see yet. */
    private fun enclosingClassMembersContain(offset: Int, name: String): Boolean {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject) {
                if (node.declarations.any { (it is KtNamedFunction && it.name == name) || (it is KtProperty && it.name == name) }) return true
                if (node.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }) return true
            }
            node = node.parent
        }
        return false
    }

    /** True if any enclosing class has a companion object, whose members are bare-accessible but not
     *  modeled, so the unresolved-reference diagnostic backs off to avoid false positives. */
    fun companionInScope(offset: Int): Boolean {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject && node.companionObjects.isNotEmpty()) return true
            node = node.parent
        }
        return false
    }

    /** A [name] used as a constructor call (a simple `Foo` or a qualified `pkg.Foo`/`Outer.Inner`, its last
     *  segment capitalized) → the resolved type FQN (a known type not shadowed by a local), else null. Drives
     *  constructor-argument validation. */
    fun constructorTypeFqn(name: String, offset: Int): String? {
        if (name.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return null
        if ('.' !in name && localsAt(offset).any { it.name == name }) return null
        return service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) }
    }

    fun enclosingClassFqn(offset: Int): String? {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject) return node.fqName?.asString()
            node = node.parent
        }
        return null
    }

    /** The candidate set for a bare name-reference completion: locals, enclosing members, top-level. */
    fun scopeSymbolsAt(offset: Int, namePrefix: String = ""): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        out += localsAt(offset)
        // Same-file declarations from the LIVE buffer — a just-typed `fun helper()` / `val x` / `class Foo`.
        // The symbol service indexes DISK and is not refreshed mid-session, so these would otherwise not
        // complete until the file is saved AND the analyzer rebuilt. Mirrors the live-buffer resolution in
        // [bareNameResolves] so a reference that ISN'T flagged unresolved also COMPLETES.
        out += sameFileScopeSymbols(offset)
        // Members of every implicit `this` (apply/with/run block, extension fn, enclosing class).
        implicitReceiversAt(offset).forEach { recv ->
            out += service.membersOf(recv.qualifiedName, recv.typeArguments, null).filterIsInstance<KotlinSymbol>()
        }
        // Locals/implicit members are small, so filter them here; the classpath top-level universe is large, so
        // [topLevelCallables] filters by prefix itself rather than materializing all of it (empty = all).
        val scoped = if (namePrefix.isEmpty()) out else out.filter { it.name.startsWith(namePrefix, ignoreCase = true) }
        return scoped + service.topLevelCallables(namePrefix)
    }

    /**
     * Symbols for declarations visible by simple name in the LIVE buffer that the disk-based symbol model may
     * not carry yet: this file's top-level functions/properties/types plus the members of every enclosing
     * class. Extensions are excluded (not callable by bare name). Signatures match [KotlinSymbolService]'s
     * `toSymbol` so the completion de-dup folds a live symbol together with its already-indexed disk twin.
     */
    private fun sameFileScopeSymbols(offset: Int): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        for (d in ktFile.declarations) when (d) {
            is KtNamedFunction -> if (d.receiverTypeReference == null) out += sameFileFunction(d, null)
            is KtProperty -> if (d.receiverTypeReference == null) out += sameFileProperty(d, null)
            is KtClassOrObject -> out += sameFileType(d)
            else -> {}
        }
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject) {
                val ownerFqn = node.fqName?.asString()
                for (d in node.declarations) when (d) {
                    is KtNamedFunction -> if (d.receiverTypeReference == null) out += sameFileFunction(d, ownerFqn)
                    is KtProperty -> if (d.receiverTypeReference == null) out += sameFileProperty(d, ownerFqn)
                    else -> {}
                }
                node.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
                    out += KotlinSymbol(
                        p.name ?: "_", SymbolKind.FIELD,
                        type = service.typeFromText(p.typeReference?.text, fileContext), origin = SOURCE,
                        owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
                        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                    )
                }
            }
            node = node.parent
        }
        return out
    }

    private fun sameFileFunction(fn: KtNamedFunction, ownerFqn: String?): KotlinSymbol {
        val params = fn.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
        val retText = fn.typeReference?.text
        val sig = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" } ?: "")
        return KotlinSymbol(
            name = fn.name ?: "_", kind = SymbolKind.METHOD,
            type = retText?.let { service.typeFromText(it, fileContext) },
            owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
            origin = SOURCE, signature = sig,
            paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext) },
            paramNames = params.map { (n, _) -> n },
            paramHasDefault = fn.valueParameters.map { it.hasDefaultValue() },
            varargParamIndex = fn.valueParameters.indexOfFirst { it.isVarArg },
            isComposable = fn.annotationEntries.any { it.shortName?.asString() == "Composable" },
            isDeprecated = fn.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
            // Top-level callables carry their package for import-visibility; members don't (mirror toSymbol).
            packageName = if (ownerFqn == null) fileContext.packageName.ifEmpty { null } else null,
            declarationNode = runCatching { parsed.adapt(fn) }.getOrNull(),
        )
    }

    private fun sameFileProperty(p: KtProperty, ownerFqn: String?): KotlinSymbol {
        val retText = p.typeReference?.text
        return KotlinSymbol(
            name = p.name ?: "_", kind = SymbolKind.FIELD,
            type = retText?.let { service.typeFromText(it, fileContext) } ?: inferType(p.initializer),
            owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
            origin = SOURCE, signature = retText?.let { ": $it" } ?: "",
            isDeprecated = p.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
            packageName = if (ownerFqn == null) fileContext.packageName.ifEmpty { null } else null,
            declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
        )
    }

    private fun sameFileType(c: KtClassOrObject): KotlinSymbol {
        val cls = c as? KtClass
        val kind = when {
            cls?.isInterface() == true -> SymbolKind.INTERFACE
            cls?.isEnum() == true -> SymbolKind.ENUM
            cls?.isAnnotation() == true -> SymbolKind.ANNOTATION_TYPE
            else -> SymbolKind.CLASS
        }
        return KotlinSymbol(
            name = c.name ?: "_", kind = kind,
            type = c.fqName?.asString()?.let { service.typeByFqn(it) },
            origin = SOURCE,
            declarationNode = runCatching { parsed.adapt(c) }.getOrNull(),
        )
    }

    // --- named arguments / expected type / overrides ---

    /** A value parameter's name + (resolved) type — for named-argument completion and arg expected-type. */
    data class ParamInfo(val name: String, val type: KotlinType?)

    /** Every function/constructor a [call] could resolve to: a `recv.foo(…)` member, else top-level + in-scope
     *  functions + the constructors of a capitalized callee (source and classpath). Used to surface a call's
     *  parameters; resolution stays best-effort (overloads are all returned, the consumer unions them). */
    fun callTargets(call: KtCallExpression): List<KotlinSymbol> {
        callTargetsCache[call]?.let { return it }
        return computeCallTargets(call).also { callTargetsCache[call] = it }
    }

    private fun computeCallTargets(call: KtCallExpression): List<KotlinSymbol> {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
        val q = call.parent as? KtQualifiedExpression
        val receiverType = if (q != null && q.selectorExpression === call) inferType(q.receiverExpression) else null
        if (receiverType != null) {
            val members = service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                .filter { it.kind == SymbolKind.METHOD }
            // Member-extensions of an in-scope implicit receiver: `Modifier.weight(…)` resolves inside `Row { }`
            // because `RowScope` (the content lambda's receiver) declares `fun Modifier.weight()`, and a
            // `fun Map<…>.printMap()` declared in the enclosing class resolves on a `Map`. Scope-gated, so the
            // extension never leaks onto a receiver outside its declaring scope.
            val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
            return if (scopeExts.isEmpty()) members else members + scopeExts
        }
        val out = ArrayList<KotlinSymbol>()
        // The callee name is known, so push it down as the prefix (it filters to names starting with it, the
        // exact match included) — avoids scanning the whole top-level universe just to keep one name.
        out += scopeSymbolsAt(call.textRange.startOffset, name).filter { it.name == name && it.kind == SymbolKind.METHOD }
        // A capitalized callee is a constructor call (`Foo(…)`): its parameters come from the type's constructors.
        if (name.firstOrNull()?.isUpperCase() == true) {
            service.resolveTypeName(name, fileContext)?.let { fqn ->
                out += service.constructorsOf(fqn)
                service.sourceClass(fqn)?.constructors?.forEach { rc -> out += sourceCtorSymbol(rc, fqn) }
            }
        }
        return out
    }

    /**
     * Member-extension callables in scope at [offset] applicable to a receiver of [receiverType] — an extension
     * declared INSIDE a class/object whose instance is an implicit `this` here, so it resolves like a member
     * WITHOUT an import. Two cases: `RowScope`'s `fun Modifier.weight()` applies to a `Modifier` while inside a
     * `Row { }` (the lambda's receiver scope), and a `fun Map<…>.printMap()` declared in the class you're editing
     * applies to a `Map` value inside that class. Sourced from BOTH the LIVE buffer's enclosing classes (a
     * just-typed extension, before the disk model catches up) and the symbol model's implicit-receiver types (a
     * saved / cross-file declaring class, or a `with(x){}` block receiver). Kept scope-gated for soundness, so
     * the extension never leaks onto a receiver outside its declaring scope. [namePrefix] empty = all.
     */
    fun scopeMemberExtensions(offset: Int, receiverType: KotlinType, namePrefix: String = ""): List<KotlinSymbol> {
        if (receiverType.isTypeParameter) return emptyList()
        val recvTargets = (listOf(receiverType.qualifiedName) + service.supertypesOf(receiverType.qualifiedName)
            .filterIsInstance<KotlinType>().map { it.qualifiedName }).toHashSet()
        fun matches(n: String) = namePrefix.isEmpty() || n.startsWith(namePrefix, ignoreCase = true)
        val out = ArrayList<KotlinSymbol>()
        val liveOwners = HashSet<String>()
        // (a) Live-buffer enclosing classes/objects — covers an extension just typed in the file being edited
        //     (the disk-based symbol model may not carry it yet).
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject) {
                node.fqName?.asString()?.let { liveOwners += it }
                for (d in node.declarations) {
                    if (d !is KtCallableDeclaration || d.receiverTypeReference == null) continue
                    val name = d.name ?: continue
                    if (!matches(name)) continue
                    val recvFqn = service.resolveTypeName(d.receiverTypeReference!!.text, fileContext) ?: continue
                    if (recvFqn !in recvTargets) continue
                    sameFileMemberExtension(d, recvFqn)?.let { out += bindMemberExtensionReceiver(it, receiverType) }
                }
            }
            node = node.parent
        }
        // (b) Model implicit-receiver types (an extension-fn receiver, a `with`/`apply` block receiver, or a
        //     saved / cross-file enclosing class) — query their members for matching extensions.
        for (scope in implicitReceiversAt(offset)) {
            if (scope.qualifiedName in liveOwners) continue // already covered from the live buffer above
            service.membersForCompletion(scope.qualifiedName, scope.typeArguments, namePrefix)
                .filter { it.isExtension && it.receiverTypeFqn != null && it.receiverTypeFqn in recvTargets }
                .forEach { out += bindMemberExtensionReceiver(it, receiverType) }
        }
        return out
    }

    /** Bind a member-extension's receiver type parameters from the actual [receiverType] (`fun <T> List<T>.x()`
     *  on `List<String>` → T = String), so its return/param types resolve concretely. */
    private fun bindMemberExtensionReceiver(ext: KotlinSymbol, receiverType: KotlinType): KotlinSymbol {
        val bindings = HashMap<String, TypeRef>()
        ext.receiverTypeParam?.let { bindings[it] = receiverType }
        ext.receiverTypeArgs.forEachIndexed { i, ra ->
            val k = ra as? KotlinType ?: return@forEachIndexed
            if (k.isTypeParameter && i < receiverType.typeArguments.size) bindings[k.qualifiedName] = receiverType.typeArguments[i]
        }
        return if (bindings.isEmpty()) ext else service.substituteSymbol(ext, bindings)
    }

    /** A symbol for a member-extension declared in the LIVE buffer (`fun Map<…>.printMap()` inside a class),
     *  with its extension [receiverFqn] and receiver type-args set so it resolves/binds like an indexed one. */
    private fun sameFileMemberExtension(d: KtCallableDeclaration, receiverFqn: String): KotlinSymbol? {
        val name = d.name ?: return null
        val recvArgs = (service.typeFromText(d.receiverTypeReference?.text, fileContext) as? KotlinType)?.typeArguments ?: emptyList()
        val declNode = runCatching { parsed.adapt(d) }.getOrNull()
        return when (d) {
            is KtNamedFunction -> {
                val params = d.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
                val retText = d.typeReference?.text
                KotlinSymbol(
                    name = name, kind = SymbolKind.METHOD,
                    type = retText?.let { service.typeFromText(it, fileContext) },
                    origin = SOURCE, receiverTypeFqn = receiverFqn,
                    signature = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" } ?: ""),
                    paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext) },
                    paramNames = params.map { (n, _) -> n },
                    paramHasDefault = d.valueParameters.map { it.hasDefaultValue() },
                    varargParamIndex = d.valueParameters.indexOfFirst { it.isVarArg },
                    isComposable = d.annotationEntries.any { it.shortName?.asString() == "Composable" },
                    receiverTypeArgs = recvArgs,
                    declarationNode = declNode,
                )
            }
            is KtProperty -> KotlinSymbol(
                name = name, kind = SymbolKind.FIELD,
                type = d.typeReference?.text?.let { service.typeFromText(it, fileContext) },
                origin = SOURCE, receiverTypeFqn = receiverFqn,
                signature = d.typeReference?.text?.let { ": $it" } ?: "",
                receiverTypeArgs = recvArgs,
                declarationNode = declNode,
            )
            else -> null
        }
    }

    private fun sourceCtorSymbol(rc: dev.ide.lang.kotlin.symbols.RawCallable, fqn: String): KotlinSymbol = KotlinSymbol(
        name = fqn.substringAfterLast('.'),
        kind = SymbolKind.CONSTRUCTOR,
        origin = SOURCE,
        paramTypes = rc.paramTexts.map { (_, t) -> service.typeFromText(t, rc.ctx) },
        paramNames = rc.paramTexts.map { (n, _) -> n },
    )

    /** The value parameters available for named-argument completion at a [call], distinct by name and with
     *  synthetic bytecode names (`p0`/`p1`) dropped. */
    fun callParameters(call: KtCallExpression): List<ParamInfo> {
        val out = LinkedHashMap<String, ParamInfo>()
        for (s in callTargets(call)) {
            s.paramNames.forEachIndexed { i, n ->
                if (n.isNotEmpty() && !isSyntheticParamName(n)) {
                    out.getOrPut(n) { ParamInfo(n, s.paramTypes.getOrNull(i) as? KotlinType) }
                }
            }
        }
        return out.values.toList()
    }

    /** ASM surfaces stripped Java parameters as `p0`, `p1`, … — useless as named arguments, so they're hidden. */
    private fun isSyntheticParamName(n: String): Boolean =
        n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

    /** Members an annotation interface carries that are NOT user-declared elements (so they're not parameters). */
    private val ANNOTATION_OBJECT_METHODS = setOf("equals", "hashCode", "toString", "annotationType")

    /** The named parameters of the annotation [entry]'s type, for argument completion inside `@Foo(…)`. A
     *  Kotlin annotation (source or `@Metadata` binary) exposes them as its constructor params; a Java
     *  annotation (`@interface`) exposes each element as a 0-arg member method, whose NAME is the parameter.
     *  Distinct by name, synthetic (`p0`) and the inherited Object/annotation methods dropped. */
    fun annotationParameters(entry: KtAnnotationEntry): List<ParamInfo> {
        val short = entry.shortName?.asString() ?: return emptyList()
        val fqn = service.resolveTypeName(short, fileContext) ?: return emptyList()
        val out = LinkedHashMap<String, ParamInfo>()
        fun addParams(symbols: List<KotlinSymbol>) {
            for (s in symbols) s.paramNames.forEachIndexed { i, n ->
                if (n.isNotEmpty() && !isSyntheticParamName(n)) {
                    out.getOrPut(n) { ParamInfo(n, s.paramTypes.getOrNull(i) as? KotlinType) }
                }
            }
        }
        addParams(service.constructorsOf(fqn))
        service.sourceClass(fqn)?.constructors?.let { ctors -> addParams(ctors.map { sourceCtorSymbol(it, fqn) }) }
        if (out.isEmpty()) {
            // Java annotation: its elements are 0-arg methods (`name()`, `widthDp()`), the method name = the param.
            for (m in service.membersForCompletion(fqn, emptyList(), "")) {
                if (m.kind == SymbolKind.METHOD && m.paramNames.isEmpty() && m.name !in ANNOTATION_OBJECT_METHODS) {
                    out.getOrPut(m.name) { ParamInfo(m.name, m.type as? KotlinType) }
                }
            }
        }
        return out.values.toList()
    }

    /**
     * The type the context at [offset] expects an expression to have, or null when unconstrained: a typed
     * property initializer (`val x: T = ‹here›`), a `return ‹here›` / expression body of a typed function, a
     * call-argument slot (positional or named), and a boolean condition (`if`/`while`/`do-while`, `&&`/`||`,
     * `!`). Powers expected-type-aware completion (rank matches first, offer enum constants + booleans).
     */
    fun expectedTypeAt(offset: Int): KotlinType? {
        var child: PsiElement? = null
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtProperty ->
                    if (child != null && child === node.initializer)
                        return node.typeReference?.text?.let { service.typeFromText(it, fileContext) }
                is KtNamedFunction ->
                    if (child != null && child === node.bodyExpression && !node.hasBlockBody())
                        return node.typeReference?.text?.let { service.typeFromText(it, fileContext) }
                is KtReturnExpression ->
                    if (child === node.returnedExpression) return enclosingFunctionReturnType(node)
                is KtValueArgument -> return expectedArgType(node)
                // A condition is wrapped in a container node, so match by range rather than child identity.
                is KtIfExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn("kotlin.Boolean")
                is KtWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn("kotlin.Boolean")
                is KtDoWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn("kotlin.Boolean")
                is KtPrefixExpression ->
                    if (node.operationToken == KtTokens.EXCL) return service.typeByFqn("kotlin.Boolean")
                is KtBinaryExpression ->
                    if (node.operationToken == KtTokens.ANDAND || node.operationToken == KtTokens.OROR)
                        return service.typeByFqn("kotlin.Boolean")
            }
            child = node
            node = node.parent
        }
        return null
    }

    private fun enclosingFunctionReturnType(from: PsiElement): KotlinType? {
        var node: PsiElement? = from.parent
        while (node != null) {
            if (node is KtNamedFunction) return node.typeReference?.text?.let { service.typeFromText(it, fileContext) }
            if (node is KtLambdaExpression) return null // a return@label leaves the lambda's type to inference
            node = node.parent
        }
        return null
    }

    private fun expectedArgType(arg: KtValueArgument): KotlinType? {
        val argList = arg.parent as? KtValueArgumentList ?: return null
        val call = argList.parent as? KtCallExpression ?: return null
        val targets = callTargets(call)
        if (targets.isEmpty()) return null
        val argName = arg.getArgumentName()?.asName?.identifier
        if (argName != null) {
            targets.forEach { s ->
                val i = s.paramNames.indexOf(argName)
                if (i >= 0) (s.paramTypes.getOrNull(i) as? KotlinType)?.let { return it }
            }
            return null
        }
        val index = argList.arguments.indexOf(arg)
        targets.forEach { s -> (s.paramTypes.getOrNull(index) as? KotlinType)?.let { return it } }
        return null
    }

    /** The inherited members overridable at [offset]: every non-final/non-private/non-static member of the
     *  enclosing class's declared supertypes, minus those it already declares. Empty outside a class with a
     *  supertype. Drives override completion. */
    fun overridableMembersAt(offset: Int): List<KotlinSymbol> {
        val cls = enclosingClassOrObject(offset) ?: return emptyList()
        val superFqns = cls.superTypeListEntries.mapNotNull { e ->
            e.typeReference?.text?.let { service.resolveTypeName(it, fileContext) }
        }
        if (superFqns.isEmpty()) return emptyList()
        val declared = cls.declarations.mapNotNull { d ->
            when (d) { is KtNamedFunction -> d.name; is KtProperty -> d.name; else -> null }
        }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<KotlinSymbol>()
        for (fqn in superFqns) {
            service.membersOf(fqn, emptyList(), null).filterIsInstance<KotlinSymbol>().forEach { m ->
                if (isOverridable(m) && m.name !in declared && seen.add(m.name + "#" + (m.signature ?: ""))) out += m
            }
        }
        return out
    }

    private fun isOverridable(m: KotlinSymbol): Boolean {
        if (m.kind != SymbolKind.METHOD && m.kind != SymbolKind.FIELD) return false
        if (m.isExtension) return false
        if (Modifier.STATIC in m.modifiers || Modifier.PRIVATE in m.modifiers || Modifier.FINAL in m.modifiers) return false
        return true
    }

    private fun localVar(p: KtProperty) = KotlinSymbol(
        name = p.name ?: "_",
        kind = SymbolKind.LOCAL_VARIABLE,
        type = service.typeFromText(p.typeReference?.text, fileContext)
            ?: inferType(p.initializer)
            ?: p.delegateExpression?.let(::delegatedValueType),
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )

    /** A `by`-delegated property's value type: the type of the delegate's `value` member (the State/Lazy
     *  `.value` convention — `by remember { mutableStateOf("") }` types as `String`). Null for a delegate that
     *  exposes no `value`, so we never fabricate a type for an unmodeled getValue convention. */
    private fun delegatedValueType(delegate: KtExpression): KotlinType? =
        inferType(delegate)?.let { memberNamed(it, "value")?.type as? KotlinType }

    /**
     * Names bound by a destructuring declaration (`val (a, b) = …`, `for ((k, v) in …)`, `{ (k, v) -> }`),
     * each typed by the corresponding `componentN()` of [sourceType] (the destructured value's type): a data
     * class's Nth property, a `Pair`/`Triple`, a `Map.Entry`'s key/value, etc. An entry with an explicit type
     * (`(a: Int, b) = …`) keeps it; an entry whose component can't be typed stays untyped (never invents one).
     */
    private fun destructuringLocals(d: KtDestructuringDeclaration, sourceType: KotlinType?): List<KotlinSymbol> =
        d.entries.mapIndexedNotNull { i, e ->
            e.name?.let { name ->
                val t = service.typeFromText(e.typeReference?.text, fileContext) ?: componentType(sourceType, i)
                KotlinSymbol(
                    name, SymbolKind.LOCAL_VARIABLE, type = t, origin = SOURCE,
                    declarationNode = runCatching { parsed.adapt(e) }.getOrNull(),
                )
            }
        }

    /**
     * The type of the value a destructuring declaration destructures: its initializer (`val (a, b) = e`), the
     * iteration element (`for ((k, v) in xs)`), or the lambda parameter it binds (`{ (k, v) -> }`). Null when
     * it can't be inferred. Powers the destructuring diagnostics (the resolver itself uses the more specific
     * source already known at each call site in [localsAt]).
     */
    fun destructuringSourceType(d: KtDestructuringDeclaration): KotlinType? {
        d.initializer?.let { return inferType(it) }
        val param = d.parent as? KtParameter ?: return null
        (param.parent as? KtForExpression)?.let { f -> if (f.loopParameter === param) return iterationElementType(inferType(f.loopRange)) }
        val literal = (param.parent as? KtParameterList)?.parent as? KtFunctionLiteral
        val lambda = literal?.parent as? KtLambdaExpression ?: return null
        val idx = literal.valueParameters.indexOf(param)
        return expectedLambdaShape(lambda)?.parameterTypes?.getOrNull(idx) as? KotlinType
    }

    /** The destructuring component type for entry [index] (0-based) of [sourceType] — the `componentN()` return
     *  type, or null when that operator isn't modeled. Public counterpart of [componentType] for diagnostics. */
    fun componentTypeFor(sourceType: KotlinType, index: Int): KotlinType? = componentType(sourceType, index)

    /** The type of the [index]-th (0-based) destructuring component of [sourceType]: the return type of its
     *  `componentN()` operator (member or extension, receiver type-args already bound). Null when the component
     *  operator isn't modeled for the type — so an unknown destructuring degrades to untyped, never wrong. */
    private fun componentType(sourceType: KotlinType?, index: Int): KotlinType? {
        val t = sourceType?.takeIf { !it.isTypeParameter } ?: return null
        return service.membersNamed(t.qualifiedName, t.typeArguments, "component${index + 1}")
            .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType
    }

    /** The element type produced by iterating [iterableType] (`for (x in xs)` → x's type): the return type of
     *  `iterator().next()` (member or extension — a `Map`'s `iterator()` is a stdlib extension yielding
     *  `Iterator<Map.Entry<K, V>>`). Null when the iterator convention isn't modeled. */
    private fun iterationElementType(iterableType: KotlinType?): KotlinType? {
        val t = iterableType?.takeIf { !it.isTypeParameter } ?: return null
        val iterator = service.membersNamed(t.qualifiedName, t.typeArguments, "iterator")
            .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType ?: return null
        return service.membersNamed(iterator.qualifiedName, iterator.typeArguments, "next")
            .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType
    }

    /** A `for` loop variable: its declared type if written (`for (x: T in …)`), else the iteration [element] type. */
    private fun loopParam(p: KtParameter, element: KotlinType?) = KotlinSymbol(
        name = p.name ?: "_",
        kind = SymbolKind.PARAMETER,
        type = service.typeFromText(p.typeReference?.text, fileContext) ?: element,
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )

    private fun param(p: KtParameter) = KotlinSymbol(
        name = p.name ?: "_",
        kind = SymbolKind.PARAMETER,
        type = service.typeFromText(p.typeReference?.text, fileContext),
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )

    private fun elementAt(offset: Int): PsiElement? {
        val len = ktFile.textLength
        if (len == 0) return null
        return ktFile.findElementAt(if (offset >= len) len - 1 else offset.coerceAtLeast(0))
    }

    companion object {
        private val SOURCE = SymbolOrigin(fromSource = true, file = null)

        /** Specificity score for an extension whose receiver IS the actual type (see [receiverSpecificity]); a
         *  supertype receiver subtracts its distance from this, so the value just needs headroom above any chain. */
        private const val RECEIVER_EXACT = 1 shl 20
        private val ARITHMETIC_CONVENTIONS = mapOf(
            KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times",
            KtTokens.DIV to "div", KtTokens.PERC to "rem",
        )

        /** Numeric-promotion ranks for arithmetic result inference — wider wins. Byte/Short/Char share the
         *  lowest rank (they promote to `Int`); Int/Long/Float/Double have distinct ranks so a result rank maps
         *  back to a unique type. */
        private val NUMERIC_RANK = mapOf(
            "kotlin.Double" to 5, "kotlin.Float" to 4, "kotlin.Long" to 3, "kotlin.Int" to 2,
            "kotlin.Short" to 1, "kotlin.Byte" to 1, "kotlin.Char" to 1,
        )
    }
}
