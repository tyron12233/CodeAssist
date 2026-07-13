package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.ValueArgument

/** Call resolution: callee binding, overload selection, call-target enumeration, and value/annotation parameter shapes. */

/** The type of a qualified call's receiver for MEMBER resolution: a bounded type-parameter receiver (`b.foo()`
 *  where `b: T`, `<T : Bound>`) resolves against `Bound` (see [receiverForMembers]); an unbounded `T` keeps its
 *  own type, so the call stays a member call with no candidates rather than falling through to top-level name
 *  resolution; any other receiver type is unchanged. Null only when the receiver can't be typed at all. */
internal fun KotlinResolver.memberReceiverOf(q: KtQualifiedExpression): KotlinType? {
    val t = inferType(q.receiverExpression) ?: return null
    return receiverForMembers(t, q.receiverExpression.textRange.startOffset) ?: t
}

private const val RECEIVER_EXACT = 1 shl 20

/** Resolve a call's callee to the best-fitting function overload (member/extension via its receiver, or
 *  top-level), with receiver type params already bound by [KotlinSymbolService.membersOf]. */
internal fun KotlinResolver.resolveCalleeFunction(call: KtCallExpression): KotlinSymbol? {
    // Not scoring: the ordinary per-snapshot [calleeCache] (a resolution is pure for the immutable parse).
    if (!scoringActive) {
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
    // SCORING (a lambda-shape override is active — [isApplicable]/[lambdaReturnSpecificity] typing a candidate's
    // lambda body): the surrounding call is mid-resolution, so a nested resolution can hit the re-entrancy
    // fallback and produce a context-dependent, provisional result. The ordinary [calleeCache] therefore stays
    // bypassed (that provisional result must not leak into the real post-scoring inference). But WITHOUT any
    // cache the same nested callee is re-resolved once per outer candidate per level — the ∏(candidate) blowup
    // that froze the editor on a deep Compose builder (`Column{Row{Surface{…}}}`). So cache it in a SEPARATE,
    // dependency-tracked map keyed by exactly the OUTER lambda-shape overrides this computation consults (via
    // [ScoringDepFrame]) — the only per-candidate-varying input to a callee resolution. An entry with no such
    // deps (the common builder case) is provably identical across sibling candidates and reused, collapsing the
    // tree to O(calls). Skipped while smart-cast narrowings are active (the lowerer), which add a flow
    // dependency this key does not capture.
    val useCache = narrowings.isEmpty()
    if (useCache) {
        scoringCalleeCache[call]?.let { e ->
            if (overridesMatch(e.deps)) {
                propagateDeps(e.deps) // reusing computation now transitively depends on whatever this entry did
                return e.result
            }
        }
    }
    if (!resolvingCallees.add(call)) return reentrantCalleeFallback(call) // re-entrant → break the cycle (don't cache)
    val frame = ScoringDepFrame()
    scoringDepFrames.addLast(frame)
    val result = try {
        computeCallee(call)
    } finally {
        scoringDepFrames.removeLast()
        resolvingCallees.remove(call)
    }
    // frame.deps now holds the outer overrides consulted during this computation ([recordOverrideConsult] also
    // recorded them into every enclosing frame, so an outer resolution's key stays correct too).
    if (useCache) scoringCalleeCache[call] = ScoringEntry(result, frame.deps)
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
internal fun KotlinResolver.reentrantCalleeFallback(call: KtCallExpression): KotlinSymbol? {
    val name =
        (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    val argCount = call.valueArguments.size
    val byName = service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
    return byName.singleOrNull { it.paramTypes.size == argCount } ?: byName.singleOrNull()
}

internal fun KotlinResolver.computeCallee(call: KtCallExpression): KotlinSymbol? {
    val name =
        (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    val argCount = call.valueArguments.size // already includes the trailing lambda
    val q = call.parent as? KtQualifiedExpression
    val receiverType = if (q != null && q.selectorExpression === call) memberReceiverOf(q) else null
    val candidates = if (receiverType != null) {
        val members =
            service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                .filter { it.kind == SymbolKind.METHOD }
        // A member-extension declared in an enclosing scope (`fun Map<…>.printMap()` in this class) resolves
        // on a matching receiver without an import — same seam completion/diagnostics use.
        val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name)
            .filter { it.name == name && it.kind == SymbolKind.METHOD }
        // A COMPANION-object function reached through the class name (`MyClass.create()`): the companion is a
        // distinct classifier the instance member lookup misses. Only for a `Type.` (not instance) receiver.
        val companionMethods = if (q != null && isTypeReceiver(q.receiverExpression))
            service.companionMembersFor(receiverType.qualifiedName, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
        else emptyList()
        members + scopeExts + companionMethods
    } else {
        // Top-level functions (`Text`, `Column`, `remember`, …) resolve via the cheap exact lookup. Only when
        // none matches do we pay for the scope-aware lookup, which also finds a bare-called scope EXTENSION
        // (`itemsIndexed(...)` inside `LazyColumn { }`, on the implicit `LazyListScope`) so its `itemContent`
        // function type + receiver is seen. This keeps the common path off the expensive recursive walk.
        service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
            .ifEmpty {
                scopeSymbolsAt(
                    call.textRange.startOffset,
                    name,
                    exactName = true
                ).filter { it.name == name && it.kind == SymbolKind.METHOD }
            }
    }
    // Prefer exact arity; then functions with MORE params than supplied args (defaulted params the caller
    // omits — `Box(modifier){…}` with 4-param Box, `items(list,key){…}` vs all-4-param overloads); then
    // functions with fewer (vararg / extra trailing lambdas). Within each tier, break ties by scoring how
    // well every non-lambda argument (positional AND named) fits each candidate. That picks `items(List<T>…)`
    // over `items(Int…)` when the arg is a `List<Project>`, and the String `TextField(value=…)` overload
    // over the `TextFieldValue` one when `value` is a String.
    // A syntactic trailing lambda can bind ONLY to a function-type parameter, so drop any overload whose
    // trailing-lambda slot is a confidently NON-functional param. Otherwise `Box { }` (1 arg) matches the
    // content-LESS `Box(modifier)` (1 param) as an EXACT-arity hit and wins here — before the real
    // `Box(…, content)` overload (more params, defaulted) is ever considered — so the content lambda's
    // `@Composable`-ness is missed and a call inside it is falsely flagged as outside a composable context (the
    // reported `Card { Box { Column() } }`). Conservative: keep ALL candidates when none has a functional
    // trailing slot (an uncertain/generic/unresolved slot never over-filters), so a call never fails to resolve.
    val viable = candidates.filter { trailingLambdaSlotIsFunctional(it, call) }.ifEmpty { candidates }
    val exact = viable.filter { it.paramTypes.size == argCount }
    if (exact.isNotEmpty()) return bestOverload(exact, call, receiverType)
    val moreParams =
        viable.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size > argCount }
    if (moreParams.isNotEmpty()) return bestOverload(moreParams, call, receiverType)
    val fewerParams =
        viable.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size < argCount }
    if (fewerParams.isNotEmpty()) return bestOverload(fewerParams, call, receiverType)
    return viable.firstOrNull()
}

/** Whether the parameter a SYNTACTIC trailing lambda in [call] would fill on [sym] is a function-type (or a Java
 *  SAM / `@Composable`) parameter — a lambda argument can bind only to such a slot. True when [call] has no
 *  trailing lambda, or the slot is a type parameter / unresolved (uncertain — never over-filter). This is what
 *  lets a `Box { }` call skip the content-LESS `Box(modifier)` overload for the real `Box(…, content)` one. */
private fun KotlinResolver.trailingLambdaSlotIsFunctional(sym: KotlinSymbol, call: KtCallExpression): Boolean {
    val lastIdx = call.valueArguments.lastIndex
    if (lastIdx < 0) return true
    val last = call.valueArguments[lastIdx]
    if (last !is KtLambdaArgument && last.getArgumentExpression() !is KtLambdaExpression) return true
    val pt = sym.paramTypes.getOrNull(lambdaParamIndex(call, lastIdx, sym)) as? KotlinType ?: return true
    if (pt.isTypeParameter) return true
    return TypeRendering.isFunctionType(pt.qualifiedName) || pt.isExtensionFunctionType || pt.isComposable ||
        service.functionalShape(pt) != null
}

/**
 * Resolve same-arity-tier [candidates] the compiler's way: FILTER to the APPLICABLE ones ([isApplicable] —
 * discard any whose argument constraints contradict), then pick the MOST SPECIFIC survivor. If every candidate
 * is (conservatively) rejected, fall back to the full set so a call never fails to resolve.
 *
 * "Most specific" is a partial order approximated by, in priority: a native function-type lambda parameter
 * over a Java SAM ([functionTypeBonus]); the most-specific RECEIVER ([receiverSpecificity] — a member beats an
 * extension, an extension on the actual type beats one on a supertype); then the most-specific PARAMETER types
 * ([paramMoreSpecific] — `h(String)` over `h(Any)`); else declaration/lookup order (so a confident resolution
 * is stable). The applicability filter subsumes the old argument-fit score: `items(List<T>)` over `items(Int)`
 * for a `List` argument, the `String` `TextField(value=…)` overload over the `TextFieldValue` one, and the
 * `sumOf`/`maxOf` selector-return disambiguation all fall out of discarding the contradicting candidates.
 */
internal fun KotlinResolver.bestOverload(
    candidates: List<KotlinSymbol>,
    call: KtCallExpression,
    receiverType: KotlinType?
): KotlinSymbol {
    if (candidates.size == 1) return candidates.first()
    val applicable = candidates.filter { isApplicable(it, call, receiverType) }
    val pool = applicable.ifEmpty { candidates }
    if (pool.size == 1) return pool.first()
    var best = pool.first()
    var bestKey = specificityKey(best, call, receiverType)
    for (c in pool.drop(1)) {
        val key = specificityKey(c, call, receiverType)
        if (key.first > bestKey.first ||
            (key.first == bestKey.first && key.second > bestKey.second) ||
            (key.first == bestKey.first && key.second == bestKey.second && key.third > bestKey.third) ||
            (key == bestKey && paramMoreSpecific(c, best))
        ) {
            best = c; bestKey = key
        }
    }
    return best
}

/** The most-specific ranking key for [sym] at [call] (higher is more specific): function-type parameter over
 *  a Java SAM, then lambda-return specificity, then receiver specificity. Parameter specificity
 *  ([paramMoreSpecific]) is the final tiebreak, applied in [bestOverload] since it's a pairwise relation. */
private fun KotlinResolver.specificityKey(
    sym: KotlinSymbol,
    call: KtCallExpression,
    receiverType: KotlinType?
): Triple<Int, Int, Int> =
    Triple(
        functionTypeBonus(sym, call),
        lambdaReturnSpecificity(sym, call),
        receiverType?.let { receiverSpecificity(sym, it) } ?: 0)

/** Whether [a]'s value-parameter types are strictly more specific than [b]'s — each is [b]'s type or a subtype,
 *  and at least one is a strict subtype. The most-specific-overload tiebreak; conservative (an unknown or
 *  type-parameter parameter, or a param [a] doesn't refine, makes it non-comparable → false). */
internal fun KotlinResolver.paramMoreSpecific(a: KotlinSymbol, b: KotlinSymbol): Boolean {
    if (a.paramTypes.size != b.paramTypes.size || a.paramTypes.isEmpty()) return false
    var strict = false
    for (i in a.paramTypes.indices) {
        val ap = a.paramTypes[i] as? KotlinType ?: return false
        val bp = b.paramTypes[i] as? KotlinType ?: return false
        if (ap.isTypeParameter || bp.isTypeParameter) return false
        when {
            ap.qualifiedName == bp.qualifiedName -> {}
            bp.isAssignableFrom(ap) -> strict =
                true      // a's param is a subtype of b's → more specific here
            else -> return false                          // a's param is not a subtype of b's → not more specific
        }
    }
    return strict
}

/** How specifically a candidate applies to a receiver of [receiverType] (higher = more specific) — the
 *  most-specific-receiver tiebreaker among overloads that fit the arguments equally well. A plain member
 *  outranks any extension; among extensions, one declared on the actual type beats one on a supertype
 *  (`String.removePrefix` over `CharSequence.removePrefix`), with a nearer supertype beating a farther one,
 *  mirroring Kotlin's overload resolution. An extension with no/unknown receiver, or one on an unrelated
 *  type, ranks lowest (0). */
internal fun KotlinResolver.receiverSpecificity(sym: KotlinSymbol, receiverType: KotlinType): Int {
    if (!sym.isExtension) return Int.MAX_VALUE
    val recv = sym.receiverTypeFqn?.let { Builtins.kotlinTypeFor(it) ?: it } ?: return 0
    val actual = Builtins.kotlinTypeFor(receiverType.qualifiedName) ?: receiverType.qualifiedName
    if (recv == actual) return RECEIVER_EXACT
    val idx = service.supertypesOf(actual)
        .indexOfFirst { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == recv }
    return if (idx >= 0) RECEIVER_EXACT - 1 - idx else 0
}

/**
 * Whether [sym] is APPLICABLE to [call]'s arguments — the compiler's applicability step. Each argument adds a
 * subtyping constraint on the parameter it fills (a lambda argument's BODY result, typed top-down from the
 * parameter's functional shape, constrains that shape's return); the candidate is inapplicable iff the
 * constraint system finds a CONTRADICTION. This is what discards `sumOf`'s `(T) -> Long` for an `Int`-returning
 * selector (`Int <: Long` fails) and `maxOf`'s `(T) -> Double` for an `Int` body, leaving the applicable
 * overload to win. CONSERVATIVE: a contradiction is only recorded on a confident concrete mismatch (see
 * [KotlinConstraintSystem]), so an unknown/partial type never rejects a candidate the compiler would accept —
 * and [bestOverload] falls back to the full set if every candidate is (wrongly) rejected, so a call never
 * fails to resolve.
 */
internal fun KotlinResolver.isApplicable(
    sym: KotlinSymbol,
    call: KtCallExpression,
    receiverType: KotlinType?
): Boolean {
    if (sym.typeParameters.isEmpty() && call.typeArgumentList == null) {
        // Non-generic candidate: no type variables, so applicability is just per-argument concrete subtyping.
        val cs = KotlinConstraintSystem(service)
        addArgumentConstraints(sym, call, cs)
        return !cs.hasContradiction
    }
    val cs = newConstraintSystem(sym)
    addArgumentConstraints(sym, call, cs)
    return !cs.hasContradiction
}

/** Add each argument's `argType <: paramType` constraint to [cs] (a lambda's body result to the parameter's
 *  functional return, typed top-down). Shared by applicability and (via the driver) type-argument inference. */
private fun KotlinResolver.addArgumentConstraints(
    sym: KotlinSymbol,
    call: KtCallExpression,
    cs: KotlinConstraintSystem
) {
    call.valueArguments.forEachIndexed { i, arg ->
        if (cs.hasContradiction) return
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        if (expr is KtLambdaExpression || arg is KtLambdaArgument) {
            val pt = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType
                ?: return@forEachIndexed
            val shape = service.functionalShape(pt) ?: return@forEachIndexed
            val lambda = expr as? KtLambdaExpression ?: return@forEachIndexed
            val bodyResult =
                withLambdaShape(lambda, shape) { inferLambdaResult(lambda) as? KotlinType }
            if (bodyResult != null) cs.addSubtypeConstraint(bodyResult, shape.returnType)
        } else {
            val pt = sym.paramTypes.getOrNull(argParamIndex(arg, i, sym)) as? KotlinType
                ?: return@forEachIndexed
            inferType(expr)?.takeIf { !it.isTypeParameter }?.let { cs.addSubtypeConstraint(it, pt) }
        }
    }
}

/**
 * Prefer a native Kotlin function-type parameter over a Java SAM when a lambda fills the slot — the sole
 * remaining fit signal (argument/return fit is now handled by [isApplicable]). Kotlin binds a lambda to a
 * function type directly but reaches a Java functional interface only through SAM conversion, ranked strictly
 * lower — so `map.forEach { e -> }` picks the Kotlin `Map.forEach((Map.Entry) -> Unit)` extension over the
 * inherited `java.util.Map.forEach(BiConsumer)` member (whose SAM's first parameter is the KEY). +1 per such
 * lambda argument.
 */
internal fun KotlinResolver.functionTypeBonus(sym: KotlinSymbol, call: KtCallExpression): Int {
    var score = 0
    call.valueArguments.forEachIndexed { i, arg ->
        val expr = arg.getArgumentExpression()
        if (arg is KtLambdaArgument || expr is KtLambdaExpression) {
            val pt = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType
                ?: return@forEachIndexed
            if (TypeRendering.isFunctionType(pt.qualifiedName) || pt.isExtensionFunctionType || pt.isComposable) score += 1
        }
    }
    return score
}

/**
 * How specifically [sym]'s lambda parameters match their argument bodies — the most-specific tiebreak AMONG
 * applicable candidates. For each lambda whose parameter has a CONCRETE non-`Unit` functional return, +2 when
 * the body's (top-down-typed) result equals that return, +1 when it is merely assignable to it. This is what
 * picks `sumOf`'s `(T) -> Int` selector over its `(T) -> Double`/`BigDecimal` siblings for an `Int`-returning
 * lambda; unlike a pure applicability filter it stays correct even when a sibling's return type isn't on the
 * classpath (so `Int <: BigDecimal` can't be *confirmed* contradictory — the exact `Int` match still wins).
 * No penalty: rejection is [isApplicable]'s job, so this only ever promotes the more specific overload.
 */
internal fun KotlinResolver.lambdaReturnSpecificity(
    sym: KotlinSymbol,
    call: KtCallExpression
): Int {
    var score = 0
    call.valueArguments.forEachIndexed { i, arg ->
        val expr = arg.getArgumentExpression() as? KtLambdaExpression ?: return@forEachIndexed
        val pt = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType
            ?: return@forEachIndexed
        val shape = service.functionalShape(pt) ?: return@forEachIndexed
        val ret = shape.returnType as? KotlinType ?: return@forEachIndexed
        if (ret.isTypeParameter || ret.qualifiedName == "kotlin.Unit" || !service.isKnownType(ret.qualifiedName)) return@forEachIndexed
        val body = withLambdaShape(expr, shape) { inferLambdaResult(expr) as? KotlinType }
            ?: return@forEachIndexed
        if (body.isTypeParameter) return@forEachIndexed
        score += when {
            body.qualifiedName == ret.qualifiedName -> 2
            ret.isAssignableFrom(body) -> 1
            else -> 0
        }
    }
    return score
}

/** The declared parameter index a non-lambda value argument fills: a NAMED argument by its name (else its
 *  positional index). The trailing-lambda variant is [lambdaParamIndex]. */
internal fun KotlinResolver.argParamIndex(
    arg: ValueArgument,
    argIndex: Int,
    sym: KotlinSymbol
): Int {
    arg.getArgumentName()?.asName?.identifier?.let { n ->
        sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it }
    }
    return argIndex
}

/** The function a [call] resolves to (the single best overload by arity), exposed for callers that need to
 *  inspect the callee — e.g. the Compose calling-convention check (is the callee `@Composable`?). */
fun KotlinResolver.calleeFunctionOf(call: KtCallExpression): KotlinSymbol? =
    resolveCalleeFunction(call)

/** The infix function a binary `left <name> right` resolves to — a custom `infix fun`, `to`, `until`, … : a
 *  single-param member of the left type (member-first), else a single-param extension. Null for an
 *  operator-token binary (`+`, `<`) or when unresolved. Exposed for semantic highlighting of an infix call. */
fun KotlinResolver.resolveInfixFunction(e: KtBinaryExpression): KotlinSymbol? {
    if (e.operationToken != KtTokens.IDENTIFIER) return null
    val name = e.operationReference.getReferencedName()
    val leftType = inferType(e.left) ?: return null
    return service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
        ?: service.extensionsFor(
            leftType.qualifiedName,
            leftType.typeArguments,
            name,
            exactName = true
        )
            .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
}

/** Every function/constructor a [call] could resolve to: a `recv.foo(…)` member, else top-level + in-scope
 *  functions + the constructors of a capitalized callee (source and classpath). Used to surface a call's
 *  parameters; resolution stays best-effort (overloads are all returned, the consumer unions them). */
fun KotlinResolver.callTargets(call: KtCallExpression): List<KotlinSymbol> {
    if (KotlinResolverStats.enabled) KotlinResolverStats.callTargetsCalls++
    if (scoringActive) return computeCallTargets(call) // don't cache a scoring-time (provisional) result
    callTargetsCache[call]?.let { return it }
    if (KotlinResolverStats.enabled) KotlinResolverStats.callTargetsComputes++
    return computeCallTargets(call).also { callTargetsCache[call] = it }
}

internal fun KotlinResolver.computeCallTargets(call: KtCallExpression): List<KotlinSymbol> {
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        ?: return emptyList()
    val q = call.parent as? KtQualifiedExpression
    val receiverType = if (q != null && q.selectorExpression === call) memberReceiverOf(q) else null
    if (receiverType != null) {
        val members =
            service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                .filter { it.kind == SymbolKind.METHOD }
        // Member-extensions of an in-scope implicit receiver: `Modifier.weight(…)` resolves inside `Row { }`
        // because `RowScope` (the content lambda's receiver) declares `fun Modifier.weight()`, and a
        // `fun Map<…>.printMap()` declared in the enclosing class resolves on a `Map`. Scope-gated, so the
        // extension never leaks onto a receiver outside its declaring scope.
        val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name)
            .filter { it.name == name && it.kind == SymbolKind.METHOD }
        val companionMethods = if (q != null && isTypeReceiver(q.receiverExpression))
            service.companionMembersFor(receiverType.qualifiedName, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
        else emptyList()
        return members + scopeExts + companionMethods
    }
    val out = ArrayList<KotlinSymbol>()
    // The callee name is known, so push it down as the prefix (it filters to names starting with it, the
    // exact match included) — avoids scanning the whole top-level universe just to keep one name.
    out += scopeSymbolsAt(
        call.textRange.startOffset,
        name,
        exactName = true
    ).filter { it.name == name && it.kind == SymbolKind.METHOD }
    // A capitalized callee is a constructor call (`Foo(…)`): its parameters come from the type's constructors.
    if (name.firstOrNull()?.isUpperCase() == true) {
        service.resolveTypeName(name, fileContext)?.let { fqn ->
            out += service.constructorsOf(fqn)
            service.sourceClass(fqn)?.constructors?.forEach { rc ->
                out += sourceCtorSymbol(
                    rc,
                    fqn
                )
            }
        }
    }
    return out
}

internal fun KotlinResolver.sourceCtorSymbol(
    rc: dev.ide.lang.kotlin.symbols.RawCallable,
    fqn: String
): KotlinSymbol = KotlinSymbol(
    name = fqn.substringAfterLast('.'),
    kind = SymbolKind.CONSTRUCTOR,
    origin = SOURCE,
    paramTypes = rc.paramTexts.map { (_, t) -> service.typeFromText(t, rc.ctx) },
    paramNames = rc.paramTexts.map { (n, _) -> n },
)

/** The value parameters available for named-argument completion at a [call], distinct by name and with
 *  synthetic bytecode names (`p0`/`p1`) dropped. */
fun KotlinResolver.callParameters(call: KtCallExpression): List<ParamInfo> {
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
internal fun KotlinResolver.isSyntheticParamName(n: String): Boolean =
    n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

/** Members an annotation interface carries that are NOT user-declared elements (so they're not parameters). */
private val ANNOTATION_OBJECT_METHODS = setOf("equals", "hashCode", "toString", "annotationType")

/** The named parameters of the annotation [entry]'s type, for argument completion inside `@Foo(…)`. A
 *  Kotlin annotation (source or `@Metadata` binary) exposes them as its constructor params; a Java
 *  annotation (`@interface`) exposes each element as a 0-arg member method, whose NAME is the parameter.
 *  Distinct by name, synthetic (`p0`) and the inherited Object/annotation methods dropped. */
fun KotlinResolver.annotationParameters(entry: KtAnnotationEntry): List<ParamInfo> {
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
    service.sourceClass(fqn)?.constructors?.let { ctors ->
        addParams(ctors.map {
            sourceCtorSymbol(
                it,
                fqn
            )
        })
    }
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
