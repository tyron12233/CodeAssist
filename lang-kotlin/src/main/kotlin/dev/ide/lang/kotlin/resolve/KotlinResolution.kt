package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.parse.KotlinParsedFile
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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
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
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.ValueArgument

/** The Compose calling-convention status of a code position (see [KotlinResolver.composableContextAt]). */
enum class ComposableContext { COMPOSABLE, NON_COMPOSABLE, UNKNOWN }

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
    private val implicitReceiversCache = HashMap<Int, List<KotlinType>>()

    fun inferType(expr: KtExpression?): KotlinType? {
        if (expr == null) return null
        if (inferCache.containsKey(expr)) return inferCache[expr]
        val r = when (expr) {
            is KtParenthesizedExpression -> inferType(expr.expression)
            is KtConstantExpression -> constType(expr)
            is KtStringTemplateExpression -> service.typeByFqn("kotlin.String")
            is KtNameReferenceExpression -> typeOfName(expr.getReferencedName(), expr.textRange.startOffset)
            is KtCallExpression -> typeOfCall(expr, null)
            is KtQualifiedExpression -> typeOfQualified(expr)
            is KtThisExpression -> thisType(expr)
            is KtSuperExpression -> superType(expr)
            is KtBinaryExpression -> inferBinaryType(expr)
            is KtArrayAccessExpression -> inferArrayGet(expr)
            // `if`/`when` used as an expression: the value is one of the branches, so its type is the type of
            // a branch — the `then`/`else` of an `if`, the first typeable entry of a `when`. (A common Compose
            // pattern: `Icon(if (selected) IconA else IconB, …)` — typing the arg disambiguates the overload.)
            is KtIfExpression -> inferType(branchExpr(expr.then)) ?: inferType(branchExpr(expr.`else`))
            is KtWhenExpression -> expr.entries.firstNotNullOfOrNull { inferType(branchExpr(it.expression)) }
            else -> null
        }
        inferCache[expr] = r
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
        else -> {
            val convention = ARITHMETIC_CONVENTIONS[token] ?: return null
            val leftType = inferType(e.left) ?: return null
            if (token == KtTokens.PLUS && leftType.qualifiedName == "kotlin.String") leftType
            // Primitive numeric arithmetic (`progress * 100`): the result is the WIDER of the two operands
            // (Double > Float > Long > Int; Byte/Short/Char promote to Int) — Kotlin's promotion. Computed
            // directly because the builtin `Float.times`/etc. members carry no return type in the model, so the
            // member lookup below returns null and `(progress * 100).toInt()` couldn't resolve its receiver.
            else numericResultType(leftType, inferType(e.right))
                ?: service.membersOf(leftType.qualifiedName, leftType.typeArguments, null)
                    .filterIsInstance<KotlinSymbol>()
                    .firstOrNull { it.name == convention && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
                    ?.type as? KotlinType
        }
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
        return service.membersOf(recv.qualifiedName, recv.typeArguments, null)
            .filterIsInstance<KotlinSymbol>()
            .firstOrNull { it.name == "get" && it.kind == SymbolKind.METHOD && it.paramTypes.size == arity }
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
        // No receiver + Capitalized → a constructor call (the type itself).
        if (receiverType == null && name.firstOrNull()?.isUpperCase() == true) {
            service.resolveTypeName(name, fileContext)?.let { return service.typeByFqn(it) }
        }
        val sym = resolveCalleeFunction(call) ?: return null
        // Bind the function's OWN type parameters from the arguments (listOf("") -> List<String>; a lambda's
        // result binds R in `(…) -> R`), falling back to each parameter's erased bound when an argument can't
        // pin it (a raw `findViewById(): T` → `View`), then substitute into the return type.
        val bindings = methodTypeParamErasure(sym) + inferTypeArguments(sym, call)
        return (sym.type as? KotlinType)?.let { service.substitute(it, bindings) as? KotlinType }
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
        if (!resolvingCallees.add(call)) return null // re-entrant resolution → break the cycle (don't cache)
        val result = try {
            computeCallee(call)
        } finally {
            resolvingCallees.remove(call)
        }
        calleeCache[call] = result
        return result
    }

    private fun computeCallee(call: KtCallExpression): KotlinSymbol? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val argCount = call.valueArguments.size // already includes the trailing lambda
        val q = call.parent as? KtQualifiedExpression
        val receiverType = if (q != null && q.selectorExpression === call) inferType(q.receiverExpression) else null
        val candidates = if (receiverType != null) {
            service.membersOf(receiverType.qualifiedName, receiverType.typeArguments, null)
                .filterIsInstance<KotlinSymbol>().filter { it.name == name && it.kind == SymbolKind.METHOD }
        } else {
            // Top-level functions (`Text`, `Column`, `remember`, …) resolve via the cheap exact lookup. Only when
            // none matches do we pay for the scope-aware lookup, which also finds a bare-called scope EXTENSION
            // (`itemsIndexed(...)` inside `LazyColumn { }`, on the implicit `LazyListScope`) so its `itemContent`
            // function type + receiver is seen. This keeps the common path off the expensive recursive walk.
            service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
                .ifEmpty { scopeSymbolsAt(call.textRange.startOffset, name).filter { it.name == name && it.kind == SymbolKind.METHOD } }
        }
        return candidates.firstOrNull { it.paramTypes.size == argCount }
            ?: candidates.firstOrNull { it.paramTypes.isNotEmpty() && it.paramTypes.size <= argCount }
            ?: candidates.firstOrNull()
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
        call.valueArguments.forEachIndexed { i, arg ->
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

    private fun memberNamed(type: KotlinType, name: String): KotlinSymbol? =
        service.membersOf(type.qualifiedName, type.typeArguments, null).filterIsInstance<KotlinSymbol>().firstOrNull { it.name == name }

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
                        is KtDestructuringDeclaration -> out += destructuringLocals(st)
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
                    lp.destructuringDeclaration?.let { out += destructuringLocals(it) } ?: run { out += param(lp) }
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
                            out += KotlinSymbol(
                                p.name ?: "_", SymbolKind.PARAMETER, type = t, origin = SOURCE,
                                declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                            )
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

    private fun enclosingClassOrObject(offset: Int): KtClassOrObject? {
        var node: PsiElement? = elementAt(offset)
        while (node != null) { if (node is KtClassOrObject) return node; node = node.parent }
        return null
    }

    /** Whether a bare [name] resolves to anything in scope (local, implicit-receiver member, top-level,
     *  import, or a known type). Used by the unresolved-reference diagnostic. */
    fun bareNameResolves(name: String, offset: Int): Boolean {
        if (localsAt(offset).any { it.name == name }) return true
        // Top-level declarations in THIS live file (the module index is disk-based and may lag the buffer).
        if (ktFile.declarations.any { (it is KtNamedFunction && it.name == name) || (it is KtProperty && it.name == name) }) return true
        // Members of an ENCLOSING class of the live buffer — the symbol service indexes disk, not the file
        // being edited, so a same-file member (`field`, `helper()`) won't appear in membersOf() below.
        if (enclosingClassMembersContain(offset, name)) return true
        if (implicitReceiversAt(offset).any { recv ->
                service.membersOf(recv.qualifiedName, recv.typeArguments, null).any { it.name == name }
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
        // Members of every implicit `this` (apply/with/run block, extension fn, enclosing class).
        implicitReceiversAt(offset).forEach { recv ->
            out += service.membersOf(recv.qualifiedName, recv.typeArguments, null).filterIsInstance<KotlinSymbol>()
        }
        // Locals/implicit members are small, so filter them here; the classpath top-level universe is large, so
        // [topLevelCallables] filters by prefix itself rather than materializing all of it (empty = all).
        val scoped = if (namePrefix.isEmpty()) out else out.filter { it.name.startsWith(namePrefix, ignoreCase = true) }
        return scoped + service.topLevelCallables(namePrefix)
    }

    // --- named arguments / expected type / overrides ---

    /** A value parameter's name + (resolved) type — for named-argument completion and arg expected-type. */
    data class ParamInfo(val name: String, val type: KotlinType?)

    /** Every function/constructor a [call] could resolve to: a `recv.foo(…)` member, else top-level + in-scope
     *  functions + the constructors of a capitalized callee (source and classpath). Used to surface a call's
     *  parameters; resolution stays best-effort (overloads are all returned, the consumer unions them). */
    fun callTargets(call: KtCallExpression): List<KotlinSymbol> {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
        val q = call.parent as? KtQualifiedExpression
        val receiverType = if (q != null && q.selectorExpression === call) inferType(q.receiverExpression) else null
        if (receiverType != null) {
            val members = service.membersOf(receiverType.qualifiedName, receiverType.typeArguments, null)
                .filterIsInstance<KotlinSymbol>().filter { it.name == name && it.kind == SymbolKind.METHOD }
            // Member-extensions of an in-scope implicit receiver: `Modifier.weight(…)` resolves inside `Row { }`
            // because `RowScope` (the content lambda's receiver) declares `fun Modifier.weight()`. Only an
            // extension whose receiver matches the call's explicit receiver type — kept scope-gated, so `weight`
            // never leaks onto a `Modifier.` outside a `RowScope`.
            val scopeExts = memberExtensionsInScope(call.textRange.startOffset, name, receiverType)
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

    /** Member-extension functions named [name] declared by any implicit receiver in scope at [offset] whose
     *  extension receiver matches [receiverType] (or a supertype of it) — e.g. `RowScope`'s `fun Modifier.weight`
     *  applies to a `Modifier` receiver while inside a `Row { }` block. Kept scope-gated for soundness. */
    private fun memberExtensionsInScope(offset: Int, name: String, receiverType: KotlinType): List<KotlinSymbol> {
        val recvTargets = (listOf(receiverType.qualifiedName) + service.supertypesOf(receiverType.qualifiedName)
            .filterIsInstance<KotlinType>().map { it.qualifiedName }).toHashSet()
        val out = ArrayList<KotlinSymbol>()
        for (scope in implicitReceiversAt(offset)) {
            service.membersOf(scope.qualifiedName, scope.typeArguments, null).filterIsInstance<KotlinSymbol>()
                .filterTo(out) {
                    it.name == name && it.kind == SymbolKind.METHOD && it.receiverTypeFqn != null && it.receiverTypeFqn in recvTargets
                }
        }
        return out
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

    /** Names bound by a destructuring declaration (`val (a, b) = …`, `for ((k, v) in …)`); types deferred. */
    private fun destructuringLocals(d: KtDestructuringDeclaration): List<KotlinSymbol> =
        d.entries.mapNotNull { e ->
            e.name?.let {
                KotlinSymbol(it, SymbolKind.LOCAL_VARIABLE, origin = SOURCE, declarationNode = runCatching { parsed.adapt(e) }.getOrNull())
            }
        }

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
