package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
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

private const val RECEIVER_EXACT = 1 shl 20

/** Resolve a call's callee to the best-fitting function overload (member/extension via its receiver, or
 *  top-level), with receiver type params already bound by [KotlinSymbolService.membersOf]. */
internal fun KotlinResolver.resolveCalleeFunction(call: KtCallExpression): KotlinSymbol? {
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
internal fun KotlinResolver.reentrantCalleeFallback(call: KtCallExpression): KotlinSymbol? {
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    val argCount = call.valueArguments.size
    val byName = service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
    return byName.singleOrNull { it.paramTypes.size == argCount } ?: byName.singleOrNull()
}

internal fun KotlinResolver.computeCallee(call: KtCallExpression): KotlinSymbol? {
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
            .ifEmpty { scopeSymbolsAt(call.textRange.startOffset, name, exactName = true).filter { it.name == name && it.kind == SymbolKind.METHOD } }
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
internal fun KotlinResolver.bestOverload(candidates: List<KotlinSymbol>, call: KtCallExpression, receiverType: KotlinType?): KotlinSymbol {
    if (candidates.size == 1) return candidates.first()
    var best = candidates.first()
    var bestFit = overloadFitScore(best, call)
    var bestSpec = receiverType?.let { receiverSpecificity(best, it) } ?: 0
    for (c in candidates.drop(1)) {
        val fit = overloadFitScore(c, call)
        val spec = receiverType?.let { receiverSpecificity(c, it) } ?: 0
        // Fit, then receiver specificity, then — the proper Kotlin tiebreak — the most-specific PARAMETER types
        // (`h(String)` over `h(Any)` for `h("a")`), so a tie no longer resolves arbitrarily by lookup order.
        if (fit > bestFit || (fit == bestFit && spec > bestSpec) ||
            (fit == bestFit && spec == bestSpec && paramMoreSpecific(c, best))
        ) { best = c; bestFit = fit; bestSpec = spec }
    }
    return best
}

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
            bp.isAssignableFrom(ap) -> strict = true      // a's param is a subtype of b's → more specific here
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
    val idx = service.supertypesOf(actual).indexOfFirst { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == recv }
    return if (idx >= 0) RECEIVER_EXACT - 1 - idx else 0
}

/** Score how well [sym] fits [call]'s arguments: +1 for each concrete-typed argument whose inferred type fits
 *  its mapped parameter (exact FQN or a subtype), -1 for each known-type mismatch. Neutral (0), so the scorer
 *  only ever moves a clear winner and never a guess: a type-parameter parameter, a functional parameter slot
 *  (lambda / callable reference / `(…) -> R` / SAM, too imprecise to score), a numeric-to-numeric adaptation,
 *  or an unknown type on either side. Higher means a better fit. */
internal fun KotlinResolver.overloadFitScore(sym: KotlinSymbol, call: KtCallExpression): Int {
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
internal fun KotlinResolver.isFunctionalParam(pt: KotlinType): Boolean =
    pt.qualifiedName.startsWith("kotlin.Function") || pt.isExtensionFunctionType || pt.isComposable ||
        service.functionalShape(pt) != null

/** The declared parameter index a non-lambda value argument fills: a NAMED argument by its name (else its
 *  positional index). The trailing-lambda variant is [lambdaParamIndex]. */
internal fun KotlinResolver.argParamIndex(arg: ValueArgument, argIndex: Int, sym: KotlinSymbol): Int {
    arg.getArgumentName()?.asName?.identifier?.let { n -> sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it } }
    return argIndex
}

/** The function a [call] resolves to (the single best overload by arity), exposed for callers that need to
 *  inspect the callee — e.g. the Compose calling-convention check (is the callee `@Composable`?). */
fun KotlinResolver.calleeFunctionOf(call: KtCallExpression): KotlinSymbol? = resolveCalleeFunction(call)

/** The infix function a binary `left <name> right` resolves to — a custom `infix fun`, `to`, `until`, … : a
 *  single-param member of the left type (member-first), else a single-param extension. Null for an
 *  operator-token binary (`+`, `<`) or when unresolved. Exposed for semantic highlighting of an infix call. */
fun KotlinResolver.resolveInfixFunction(e: KtBinaryExpression): KotlinSymbol? {
    if (e.operationToken != KtTokens.IDENTIFIER) return null
    val name = e.operationReference.getReferencedName()
    val leftType = inferType(e.left) ?: return null
    return service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
        ?: service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name, exactName = true)
            .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
}

/** Every function/constructor a [call] could resolve to: a `recv.foo(…)` member, else top-level + in-scope
 *  functions + the constructors of a capitalized callee (source and classpath). Used to surface a call's
 *  parameters; resolution stays best-effort (overloads are all returned, the consumer unions them). */
fun KotlinResolver.callTargets(call: KtCallExpression): List<KotlinSymbol> {
    if (KotlinResolverStats.enabled) KotlinResolverStats.callTargetsCalls++
    callTargetsCache[call]?.let { return it }
    if (KotlinResolverStats.enabled) KotlinResolverStats.callTargetsComputes++
    return computeCallTargets(call).also { callTargetsCache[call] = it }
}

internal fun KotlinResolver.computeCallTargets(call: KtCallExpression): List<KotlinSymbol> {
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
    out += scopeSymbolsAt(call.textRange.startOffset, name, exactName = true).filter { it.name == name && it.kind == SymbolKind.METHOD }
    // A capitalized callee is a constructor call (`Foo(…)`): its parameters come from the type's constructors.
    if (name.firstOrNull()?.isUpperCase() == true) {
        service.resolveTypeName(name, fileContext)?.let { fqn ->
            out += service.constructorsOf(fqn)
            service.sourceClass(fqn)?.constructors?.forEach { rc -> out += sourceCtorSymbol(rc, fqn) }
        }
    }
    return out
}

internal fun KotlinResolver.sourceCtorSymbol(rc: dev.ide.lang.kotlin.symbols.RawCallable, fqn: String): KotlinSymbol = KotlinSymbol(
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
