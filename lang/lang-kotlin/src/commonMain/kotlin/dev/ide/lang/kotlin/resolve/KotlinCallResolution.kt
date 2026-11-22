package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.ValueArgument
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.kotlin.syntax.psi.siblings
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

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

/** Whether an implicit receiver at [offset] has a member FUNCTION [name] that can take [argCount] arguments. */
private fun KotlinResolver.implicitReceiverHasMethod(offset: Int, name: String, argCount: Int): Boolean =
    implicitReceiversAt(offset).any { recv ->
        // A project class (and its project supertypes) answers from its raw declarations: building its member
        // symbols infers every expression-bodied member's type, too much to pay on each bare call.
        service.sourceMethodTakes(recv.qualifiedName, name, argCount)
            ?: service.membersNamed(recv.qualifiedName, recv.typeArguments, name)
                .any { it.name == name && it.kind == SymbolKind.METHOD && !it.isExtension && !needsMoreArguments(it, argCount) }
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
                // An intersection (`B & A` after a smart cast) has the members of every component.
                .ifEmpty {
                    receiverType.intersection.flatMap { c -> service.membersNamed(c.qualifiedName, c.typeArguments, name) }
                        .filter { it.kind == SymbolKind.METHOD }
                }
        // A member-extension declared in an enclosing scope (`fun Map<…>.printMap()` in this class) resolves
        // on a matching receiver without an import — same seam completion/diagnostics use.
        val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name, exactName = true)
            .filter { it.name == name && it.kind == SymbolKind.METHOD }
        // A COMPANION-object function reached through the class name (`MyClass.create()`): the companion is a
        // distinct classifier the instance member lookup misses. Only for a `Type.` (not instance) receiver.
        val companionMethods = if (q != null && isTypeReceiver(q.receiverExpression))
            service.companionMembersFor(receiverType.qualifiedName, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
        else emptyList()
        // A Java class Kotlin maps to a built-in, written in its Java spelling (`Integer.parseInt`,
        // `Character.isDigit`, `java.lang.Long.toBinaryString`): the classifier is a TYPE, not a value, so its
        // JVM statics are the only candidates. The mapped built-in's extensions (`Int.toHexString(HexFormat)`,
        // `Char.isDigit()`) need an instance and never apply.
        val javaStatics = q?.let { mappedJavaStatics(it.receiverExpression, receiverType, name) }.orEmpty()
            .filter { it.kind == SymbolKind.METHOD }
        if (javaStatics.isNotEmpty()) javaStatics + companionMethods
        else memberPropertyInvokeTargets(members, receiverType, name)
            ?: (kotlinCandidateOrder(members, scopeExts) + companionMethods).ifEmpty {
                extensionPropertyInvokeTargets(call, receiverType, name).ifEmpty { receiverValueInvokeTargets(call, name, receiverType) }
            }
    } else if (q != null && q.selectorExpression === call) {
        // An EXPLICIT receiver whose type is unknown (`unresolved?.let { }`). Only a package-qualified call
        // (`kotlinx.coroutines.delay(…)`) can still resolve, to a plain top-level function. The scope walk below
        // must not run: it treats the call as a BARE one and binds an extension's receiver to the enclosing
        // implicit receiver, so `x?.let { it }` inside `Column { }` typed `it` as `ColumnScope`.
        // A receiver that is a VALUE of unknown type is not a package: no top-level function is reached through it
        // (`sym.list.any { }` with `sym` untyped is not some library's top-level `any(…)`). Only a dotted name whose
        // segments name that function's package qualifies.
        val pkg = q.receiverExpression.text.filterNot { it.isWhitespace() }
        val tops = service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD && !it.isExtension && it.packageName == pkg }
        // `pkg.Foo(x)` with both a class `Foo` and a factory `fun Foo(…)` in `pkg`: the one the arguments fit.
        if (tops.isNotEmpty() && constructorFitsInstead("$pkg.$name", tops, call)) return null
        tops
    } else {
        // A nearer VALUE with an `invoke` (a function-typed property or local) shadows every top-level function
        // of that name ([nearerValueInvokeTargets]), so no FUNCTION is the callee: the call is the value's
        // invocation, which [typeOfCall] types through [invokeReturnType] and the highlighter colors as the
        // value. Null from the helper means "not that case": go on.
        if (nearerValueInvokeTargets(call, name) != null) return null
        val typeInScope = name.firstOrNull()?.isUpperCase() == true &&
            service.resolveTypeName(name, fileContext)?.let { service.isKnownType(it) } == true
        // Top-level functions (`Text`, `Column`, `remember`, …) resolve via the cheap exact lookup. Only when
        // none matches do we pay for the scope-aware lookup, which also finds a bare-called scope EXTENSION
        // (`itemsIndexed(...)` inside `LazyColumn { }`, on the implicit `LazyListScope`) so its `itemContent`
        // function type + receiver is seen. This keeps the common path off the expensive recursive walk.
        // A LOCAL function SHADOWS a same-named top-level one, so when one is in scope the top-level candidates
        // are dropped — otherwise `fun f() { fun g() {}; g() }` beside a top-level `fun g(a: Int)` bound to the
        // top-level and reported a missing argument. The scope walk only runs when a same-named top-level
        // function actually exists (otherwise the `ifEmpty` branch below already resolves the local), so the
        // common case stays on the plain index lookup.
        service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
            // Among several same-named top-levels, PREFER the ones actually in scope. `synchronized(this)`
            // bound to `kotlinx.coroutines.internal.synchronized` -- unimported, and reported as an
            // experimental-API misuse -- instead of the default-imported `kotlin.synchronized`. This only
            // REORDERS: when nothing is in scope the whole list is kept, so the leniency a bare `Text` /
            // `Column` relies on is untouched, which is what filtering outright broke.
            .let { cands ->
                cands.filter { topLevelInScope(it, fileContext) }
                    // The nearest scope level that can take the call is the only one Kotlin looks at: a same-package
                    // `testFun()` beats a star-imported one, an explicit import beats both.
                    .let { inScope ->
                        if (inScope.size < 2) inScope
                        else inScope.groupBy { topLevelScopeRank(it, fileContext) }.entries.sortedBy { it.key }.map { it.value }
                            .firstOrNull { level -> level.any { !needsMoreArguments(it, argCount) } } ?: inScope
                    }
                    .ifEmpty {
                    // None in scope, but a STAR import of a class brings its statics in (`import java.util.Collections.*`
                    // makes `max(list, cmp)` `Collections.max`): those are the candidates, not an unimported top-level.
                    importedSingletonMembers(name, exactName = true)
                        .filter { it.name == name && it.kind == SymbolKind.METHOD }
                        .takeIf { it.isNotEmpty() }?.let { return@ifEmpty it }
                    // None in scope, but the name IS a type in scope (`import android.widget.Button` and then
                    // `Button(ctx)`): that class's constructor is the call. An unimported function of the same name
                    // (Material3's `Button`) is invisible to Kotlin, and keeping it made a View constructor read as
                    // a @Composable invocation.
                    if (typeInScope) emptyList() else cands
                }
            }
            .let {
                // A local function, and equally a MEMBER of the enclosing class, SHADOWS a same-named
                // top-level one. This lookup runs BEFORE the scope walk that knows about members, so a
                // top-level hit did not merely compete with the member -- it won outright, and an UNIMPORTED
                // one that Kotlin cannot even see won too. On a classpath carrying the Kotlin compiler, a
                // class with its own `private fun typeOf()` bound to `kotlin.reflect.typeOf` (whose result has
                // no `qualifiedName`) and one with `private fun analyze(…)` bound to the Analysis API's
                // `fun <R> analyze(…): R` -- the source of "not enough information to infer type variable R"
                // on a call to a function with no type parameters.
                //
                // Dropped only when the name really is declared nearer. Filtering every out-of-scope
                // top-level instead regressed seven tests: a bare `Text`/`Column` and a bare scope member
                // resolve here without their import, and that leniency is load-bearing elsewhere.
                // So does a member of any implicit receiver, an extension function's own receiver included: K2's
                // tower visits implicit receiver scopes before the package and import scopes (`iterator()` inside
                // `fun Iterable<T>.f()` is `Iterable.iterator()`, not `kotlin.sequences.iterator(block)`).
                val shadowed = it.isNotEmpty() && (localFunctionsInScope(call.textRange.startOffset, name).isNotEmpty() ||
                    enclosingClassMembersContain(call.textRange.startOffset, name) ||
                    implicitReceiverHasMethod(call.textRange.startOffset, name, argCount))
                if (shadowed) emptyList() else it
            }
            .let { tops ->
                // A class and a same-named factory function at one level (`AtomicArray(array)` beside
                // `fun AtomicArray(size, init)`): K2 weighs the constructors with the functions, so when every
                // function is short of arguments and a constructor can take the call, the call constructs.
                if (typeInScope && tops.isNotEmpty() && tops.all { needsMoreArguments(it, argCount) } &&
                    service.resolveTypeName(name, fileContext)?.let { t -> service.constructorsOf(t).any { !needsMoreArguments(it, argCount) && it.paramTypes.size >= argCount - (if (it.varargParamIndex >= 0) 99 else 0) } } == true
                ) return null
                // The same when the functions take the count but not the TYPES (`LocalFileSystem(path.toString())`
                // beside `fun LocalFileSystem(root: Path)`).
                if (typeInScope && tops.isNotEmpty() &&
                    service.resolveTypeName(name, fileContext)?.let { constructorFitsInstead(it, tops, call) } == true
                ) return null
                // A top-level function that cannot take this many arguments is not the callee when the implicit
                // receiver has one that can: inside `fun String.pad(n: Int) = repeat(n)`, `repeat(n)` is
                // `CharSequence.repeat(n)`, not `kotlin.repeat(times, action)` with its action missing. The
                // compiler weighs both at once; this only steps in when EVERY top-level hit is short of
                // arguments, so the cheap path above still answers nearly every call.
                if (tops.isEmpty() || tops.any { !needsMoreArguments(it, argCount) }) tops
                else scopeSymbolsAt(call.textRange.startOffset, name, exactName = true)
                    .filter {
                        it.name == name && it.kind == SymbolKind.METHOD && it.isExtension && extensionInScope(it) &&
                            !needsMoreArguments(it, argCount)
                    }
                    .ifEmpty { tops }
            }
            .ifEmpty {
                scopeSymbolsAt(
                    call.textRange.startOffset,
                    name,
                    exactName = true
                ).filter { it.name == name && it.kind == SymbolKind.METHOD && (!it.isExtension || extensionInScope(it)) }
                    // The scope walk lists every top-level function too; with a type of this name in scope, an
                    // out-of-scope one is still invisible (see above).
                    .let { found ->
                        if (!typeInScope) found
                        else found.filter { f -> f.packageName == null || f.isExtension || topLevelInScope(f, fileContext) }
                    }
                    // A member of an enclosing class or implicit receiver is nearer than every top-level function the
                    // walk also lists: those are not candidates then (`fun foo(): E` in the class over a top-level `foo()`).
                    .let { found ->
                        val members = found.filter { it.owner != null && !it.isExtension }
                        if (members.isEmpty() || found.size == members.size) found
                        else if (enclosingClassMembersContain(call.textRange.startOffset, name) || implicitReceiverHasMethod(call.textRange.startOffset, name, argCount))
                            found.filter { it.owner != null || it.isExtension }
                        else found
                    }
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
    val moreParams =
        viable.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size > argCount }
    val fewerParams =
        viable.filter { it.paramTypes.isNotEmpty() && it.paramTypes.size < argCount }
    if (exact.isNotEmpty()) {
        // Normally the exact-arity tier wins. But if EVERY exact-arity candidate is INAPPLICABLE (its parameter
        // types contradict the arguments) while a defaulted/vararg overload one tier down IS applicable, that
        // lower overload is the real target: `Animatable(0.4f)` must pick `Animatable(Float, Float = …)` (2
        // params, one defaulted) over the exact-arity `Animatable(Color)` — a Float argument can't be a Color.
        // Only pay the applicability probe when such a lower-tier alternative exists, so the common
        // single-arity-tier resolution keeps its fast path (bestOverload already filters within a tier).
        if ((moreParams.isNotEmpty() || fewerParams.isNotEmpty()) && exact.none { isApplicable(it, call, receiverType) }) {
            (moreParams + fewerParams).filter { isApplicable(it, call, receiverType) }
                .ifEmpty { null }?.let { return bestOverload(it, call, receiverType) }
        }
        return bestOverload(exact, call, receiverType)
    }
    if (moreParams.isNotEmpty()) return bestOverload(moreParams, call, receiverType)
    // More arguments than parameters fit only a vararg overload: `remember(a, b, c, d) { }` is
    // `remember(vararg keys, calculation)`, never the fixed `remember(key1, key2, key3, calculation)` whose
    // last slot would swallow `d`. Fall back to the whole tier when no candidate declares a vararg.
    if (fewerParams.isNotEmpty()) {
        val pool = fewerParams.filter { it.varargParamIndex >= 0 }.ifEmpty { fewerParams }
        return bestOverload(pool, call, receiverType)
    }
    return viable.firstOrNull()
}

/**
 * Whether a call that names both the class [typeFqn] and same-level factory functions [functions] constructs:
 * no function is applicable and a (callable) constructor is. K2 weighs the class's constructors together with
 * the functions of that level, so the applicable one is the callee.
 */
private fun KotlinResolver.constructorFitsInstead(typeFqn: String, functions: List<KotlinSymbol>, call: KtCallExpression): Boolean {
    if (!service.isKnownType(typeFqn)) return false
    if (functions.any { isApplicable(it, call, null) }) return false
    val ctors = (service.constructorsOf(typeFqn) +
        service.sourceClass(typeFqn)?.constructors.orEmpty().map { sourceCtorSymbol(it, typeFqn) })
        .filter { Modifier.PRIVATE !in it.modifiers }
    return ctors.any { it.paramTypes.size == call.valueArguments.size && isApplicable(it, call, null) }
}

/** Whether [sym] has more REQUIRED parameters than the [argCount] arguments supplied (no default, not the
 *  vararg). False when its defaults are unknown, so an undecidable candidate is never ruled out. */
private fun needsMoreArguments(sym: KotlinSymbol, argCount: Int): Boolean {
    if (sym.paramHasDefault.size != sym.paramTypes.size) return false
    val required = sym.paramTypes.indices.count { it != sym.varargParamIndex && !sym.paramHasDefault[it] }
    return required > argCount
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
    // None applies: the call is in error either way, and the candidates its arguments at least map onto are
    // the ones K2 reports it against (`assertTrue(1)` is a mismatch on `actual: Boolean`).
    val pool = applicable.ifEmpty { candidates.filter { !argumentsMissParameters(it, call) }.ifEmpty { candidates } }
    if (pool.size == 1) return pool.first()
    var best = pool.first()
    var bestKey = specificityKey(best, call, receiverType)
    for (c in pool.drop(1)) {
        val key = specificityKey(c, call, receiverType)
        if (key.first > bestKey.first ||
            (key.first == bestKey.first && key.second > bestKey.second) ||
            (key.first == bestKey.first && key.second == bestKey.second && key.third > bestKey.third) ||
            (key == bestKey && paramMoreSpecific(c, best)) ||
            (key == bestKey && overridesWithNarrowerReturn(c, best)) ||
            // A candidate that needs no vararg expansion is more specific than one that does: `foo(b)` picks
            // `foo(t: A<T>)` over `foo(vararg t: T)`, and from there the narrower `foo(t: B<T>)`.
            (key == bestKey && best.varargParamIndex >= 0 && c.varargParamIndex < 0 && !paramMoreSpecific(best, c))
        ) {
            best = c; bestKey = key
        }
    }
    return best
}

/**
 * Whether [a] is an override of [b] with a narrower return type: the same parameter list, and a return type
 * that is a strict subtype of [b]'s. Both arrive as candidates because a member is collected once per supertype
 * that declares it, in lookup order, and the narrowest override is the one the call actually binds to.
 * `MutableList<T>.iterator()` reaches `Collection.iterator(): Iterator<T>` through `List` before
 * `MutableCollection.iterator(): MutableIterator<T>`, and taking the first made `it.remove()` on a list's
 * iterator "Unresolved reference: remove".
 */
private fun overridesWithNarrowerReturn(a: KotlinSymbol, b: KotlinSymbol): Boolean {
    if (a.paramTypes.size != b.paramTypes.size) return false
    for (i in a.paramTypes.indices) {
        val ap = a.paramTypes[i] ?: return false
        val bp = b.paramTypes[i] ?: return false
        if (ap.qualifiedName != bp.qualifiedName) return false
    }
    val ar = a.type as? KotlinType ?: return false
    val br = b.type as? KotlinType ?: return false
    if (ar.isTypeParameter || br.isTypeParameter || ar.qualifiedName == br.qualifiedName) return false
    return br.isAssignableFrom(ar)
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
    if (receiverType != null && receiverArgsContradict(sym, receiverType)) return false
    if (argumentsMissParameters(sym, call)) return false
    // A SPREAD argument can only fill a vararg: `listOf(*arr)` is never `listOf(element: T)`, which otherwise
    // won with `T = Array<…>`. Kotlin metadata, Java bytecode (ACC_VARARGS) and source all record the vararg
    // index; a candidate with no parameter info at all is left alone.
    if (sym.paramTypes.isNotEmpty()) call.valueArguments.forEachIndexed { i, arg ->
        if (arg.getSpreadElement() != null && argParamIndex(arg, i, sym) != sym.varargParamIndex) return false
    }
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

/**
 * Whether [call]'s arguments cannot be mapped onto [sym]'s parameters: a name it does not declare, more
 * positional arguments than it has parameters (and no vararg), or a required parameter (no default, not the
 * vararg) left without a value. K2 rejects such a candidate outright (NAMED_PARAMETER_NOT_FOUND, TOO_MANY_ARGUMENTS,
 * NO_VALUE_FOR_PARAMETER), so `assertTrue(x)` is never `assertTrue(message: String? = null, block: () -> Boolean)`.
 * Judged only when the candidate's parameter names and defaults are known (Kotlin metadata or source); a Java or
 * synthetic candidate is never ruled out here.
 */
private fun KotlinResolver.argumentsMissParameters(sym: KotlinSymbol, call: KtCallExpression): Boolean {
    val n = sym.paramTypes.size
    if (sym.paramHasDefault.size != n || sym.paramNames.size != n) return false
    val filled = BooleanArray(n)
    call.valueArguments.forEachIndexed { i, arg ->
        val name = arg.getArgumentName()?.asName?.identifier
        val idx = when {
            name != null -> sym.paramNames.indexOf(name).takeIf { it >= 0 } ?: return true
            arg is KtLambdaArgument -> lambdaParamIndex(call, i, sym)
            else -> argParamIndex(arg, i, sym)
        }
        if (idx !in 0..<n) return true
        filled[idx] = true
    }
    return (0..<n).any { !filled[it] && !sym.paramHasDefault[it] && it != sym.varargParamIndex }
}

/**
 * Whether [sym], an extension declared on a CONCRETE instantiation of its receiver (`Iterable<Double>.max()`),
 * cannot take [receiverType] because the receiver's own type arguments contradict it (a `List<String>`). Only
 * the arguments decided overloads, so `listOf("a").max()` bound to the `Double` overload and typed `Double?`.
 * Receiver arguments that are or mention a type parameter (`Iterable<T>`) never contradict, and the
 * constraint system only reports a confident concrete mismatch.
 */
private fun KotlinResolver.receiverArgsContradict(sym: KotlinSymbol, receiverType: KotlinType): Boolean {
    if (!sym.isExtension || sym.receiverTypeArgs.isEmpty()) return false
    val extRecv = sym.receiverTypeFqn?.let { Builtins.kotlinTypeFor(it) ?: it } ?: return false
    val actualFqn = Builtins.kotlinTypeFor(receiverType.qualifiedName) ?: receiverType.qualifiedName
    val actualArgs = if (extRecv == actualFqn) receiverType.typeArguments
    else service.receiverSupertypeArgs(actualFqn, receiverType.typeArguments, extRecv) ?: return false
    val cs = KotlinConstraintSystem(service)
    sym.receiverTypeArgs.forEachIndexed { i, ra ->
        val declared = ra as? KotlinType ?: return@forEachIndexed
        if (mentionsTypeParameter(declared)) return@forEachIndexed
        val actual = actualArgs.getOrNull(i) as? KotlinType ?: return@forEachIndexed
        if (mentionsTypeParameter(actual)) return@forEachIndexed
        cs.addSubtypeConstraint(actual, declared)
    }
    return cs.hasContradiction
}

private fun mentionsTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsTypeParameter) ?: true }

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
            // A lambda that declares its parameters fills only a function type of that arity, and one that
            // declares none takes zero or one (`it`): `foo { x, y -> }` rules out `foo((Int) -> Unit)`.
            val declared = lambda.functionLiteral.valueParameterList?.parameters?.size ?: 0
            val wanted = shape.parameterTypes.size
            if (if (declared == 0) wanted > 1 && lambda.functionLiteral.arrow == null else declared != wanted) {
                cs.contradict()
                return
            }
            // A `Unit`-returning block coerces its result away ([coercesResultToUnit]), so its body constrains
            // nothing: `forEach { list.add(x) }` must not be inapplicable because `add` returns `Boolean`.
            // Skipping it also spares typing every Unit content lambda's body during scoring.
            if (coercesResultToUnit(shape.returnType)) return@forEachIndexed
            val bodyResult =
                withLambdaShape(lambda, shape) { inferLambdaResult(lambda) as? KotlinType }
            if (bodyResult != null) cs.addSubtypeConstraint(bodyResult, shape.returnType)
        } else if (expr is KtCallableReferenceExpression) {
            // A callable reference fills a function-typed parameter like a lambda: its target's RESULT must fit
            // the parameter's functional return, which is what rules out `sumOf`'s `(T) -> BigDecimal` for
            // `P::age`. An unresolved target constrains nothing.
            val pt = sym.paramTypes.getOrNull(argParamIndex(arg, i, sym)) as? KotlinType
                ?: return@forEachIndexed
            val shape = service.functionalShape(pt) ?: return@forEachIndexed
            if (coercesResultToUnit(shape.returnType)) return@forEachIndexed
            callableReferenceResultType(expr)?.let { cs.addSubtypeConstraint(it, shape.returnType) }
        } else {
            val pt = sym.paramTypes.getOrNull(argParamIndex(arg, i, sym)) as? KotlinType
                ?: return@forEachIndexed
            inferType(expr)?.takeIf { !it.isTypeParameter }
                ?.let { if (arg.getSpreadElement() != null) spreadElementType(it) else it }
                ?.let { cs.addSubtypeConstraint(it, pt) }
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
        val argExpr = arg.getArgumentExpression()
        val ref = argExpr as? KtCallableReferenceExpression
        val expr = argExpr as? KtLambdaExpression
        if (expr == null && ref == null) return@forEachIndexed
        val pt = sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) as? KotlinType
            ?: return@forEachIndexed
        val shape = service.functionalShape(pt) ?: return@forEachIndexed
        val ret = shape.returnType as? KotlinType ?: return@forEachIndexed
        if (ret.isTypeParameter || ret.qualifiedName == "kotlin.Unit" || !service.isKnownType(ret.qualifiedName)) return@forEachIndexed
        // A callable reference (`sumOf(P::age)`) ranks by its target's result exactly as a lambda's body does.
        val body = (if (expr != null) withLambdaShape(expr, shape) { inferLambdaResult(expr) as? KotlinType }
        else callableReferenceResultType(ref!!)) ?: return@forEachIndexed
        if (body.isTypeParameter) return@forEachIndexed
        score += when {
            body.qualifiedName == ret.qualifiedName -> 2
            ret.isAssignableFrom(body) -> 1
            else -> 0
        }
    }
    return score
}

/** The declared parameter index a non-lambda value argument fills: a NAMED argument by its name, a positional
 *  argument at or past a vararg by the vararg, else its positional index. The trailing-lambda variant is
 *  [lambdaParamIndex]. */
internal fun KotlinResolver.argParamIndex(
    arg: ValueArgument,
    argIndex: Int,
    sym: KotlinSymbol
): Int {
    arg.getArgumentName()?.asName?.identifier?.let { n ->
        sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it }
    }
    val vararg = sym.varargParamIndex
    if (vararg in 0..<argIndex) return vararg
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
    // A lambda written as the receiver (`{ ... } foo { ... }`) is typed by itself, as K2 types a lambda nothing
    // gives an expected type: its declared parameters and its last expression (`{}` is `() -> Unit`).
    val leftType = inferType(e.left)
        ?: (stripAll(e.left) as? KtLambdaExpression)?.let { lambdaFunctionType(it) }
        ?: return null
    return service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
        // A LOCAL infix extension is nearer than any top-level or imported one.
        ?: localExtensionFunction(e.textRange.startOffset, leftType, name, KtTokens.INFIX_KEYWORD)?.let { sameFileFunction(it, null) }
        ?: service.extensionsFor(
            leftType.qualifiedName,
            leftType.typeArguments,
            name,
            exactName = true
        )
            .filter { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
            // The file's own package outranks the default imports: a project `infix fun <A, B> A.to(that: B)`
            // beside `class Pair` is the `to` there, not `kotlin.to`. Out-of-scope extensions take no part.
            .let { exts -> exts.filter { extensionInScope(it) }.ifEmpty { exts } }
            .let { exts -> exts.firstOrNull { it.origin.fromSource } ?: exts.firstOrNull() }
}

/** The nearest LOCAL extension function [name] on a supertype of [leftType] taking one argument and carrying
 *  [modifier] (`operator` for `a + b`, `infix` for `a plus b`): a local declaration outranks every top-level one. */
internal fun KotlinResolver.localExtensionFunction(
    offset: Int, leftType: KotlinType, name: String, modifier: com.intellij.platform.syntax.SyntaxElementType,
): dev.ide.kotlin.syntax.psi.KtNamedFunction? =
    localFunctionsInScope(offset, name).firstOrNull { fn ->
        val recv = fn.receiverTypeReference?.text ?: return@firstOrNull false
        fn.hasModifier(modifier) && fn.valueParameters.size == 1 &&
            service.typeFromText(recv, fileContext, enclosingClassFqnOf(fn))?.isAssignableFrom(leftType) == true
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
                // An intersection (`B & A` after a smart cast) has the members of every component.
                .ifEmpty {
                    receiverType.intersection.flatMap { c -> service.membersNamed(c.qualifiedName, c.typeArguments, name) }
                        .filter { it.kind == SymbolKind.METHOD }
                }
        // Member-extensions of an in-scope implicit receiver: `Modifier.weight(…)` resolves inside `Row { }`
        // because `RowScope` (the content lambda's receiver) declares `fun Modifier.weight()`, and a
        // `fun Map<…>.printMap()` declared in the enclosing class resolves on a `Map`. Scope-gated, so the
        // extension never leaks onto a receiver outside its declaring scope.
        val scopeExts = scopeMemberExtensions(call.textRange.startOffset, receiverType, name, exactName = true)
            .filter { it.name == name && it.kind == SymbolKind.METHOD }
        val companionMethods = if (q != null && isTypeReceiver(q.receiverExpression))
            service.companionMembersFor(receiverType.qualifiedName, name)
                .filter { it.name == name && it.kind == SymbolKind.METHOD }
        else emptyList()
        // A Java class's statics through its Java spelling (`Integer.toHexString`), the only candidates there;
        // see the matching case in [computeCallee].
        q?.let { mappedJavaStatics(it.receiverExpression, receiverType, name) }.orEmpty()
            .filter { it.kind == SymbolKind.METHOD }
            .takeIf { it.isNotEmpty() }?.let { return it + companionMethods }
        // The nested type of an OBJECT receiver, when nothing named it is a member: `Codec.Writer(1)` where
        // `object Codec { class Writer }` constructs the nested class. See the matching case in [typeOfCall].
        if (members.isEmpty() && scopeExts.isEmpty() && companionMethods.isEmpty() &&
            name.firstOrNull()?.isUpperCase() == true
        ) {
            val nested = "${receiverType.qualifiedName}.$name"
            if (service.isKnownType(nested)) {
                return service.constructorsOf(nested) +
                    service.sourceClass(nested)?.constructors.orEmpty().map { sourceCtorSymbol(it, nested) }
            }
        }
        memberPropertyInvokeTargets(members, receiverType, name)?.let { return it }
        return (kotlinCandidateOrder(members, scopeExts) + companionMethods).ifEmpty {
            extensionPropertyInvokeTargets(call, receiverType, name).ifEmpty { receiverValueInvokeTargets(call, name, receiverType) }
        }
    }
    // A nearer function-typed value: the call's targets are its `invoke`, not the top-level functions it
    // shadows ([nearerValueInvokeTargets]).
    if (q == null || q.selectorExpression !== call) nearerValueInvokeTargets(call, name)?.let { return it }
    val out = ArrayList<KotlinSymbol>()
    // The callee name is known, so push it down as the prefix (it filters to names starting with it, the
    // exact match included) — avoids scanning the whole top-level universe just to keep one name.
    // Bare-call scope candidates: keep members and IN-SCOPE extensions only. A top-level extension that isn't
    // imported (e.g. `androidx.compose.foundation.lazy.items` used bare inside `LazyColumn { }` without its
    // import) is NOT a candidate for the compiler, so it must not be one here either — otherwise the call
    // silently binds the unimported extension and the argument mismatch against the in-scope member is missed.
    // Only for a BARE call: a qualified one whose receiver could not be typed (`sym.list.any { }`) reaches nothing in
    // scope through it; the package-qualified case is handled below.
    if (q == null || q.selectorExpression !== call) out += scopeSymbolsAt(
        call.textRange.startOffset,
        name,
        exactName = true
    ).filter { it.name == name && it.kind == SymbolKind.METHOD && (!it.isExtension || extensionInScope(it)) }
    // A capitalized name that a TYPE in scope answers to (`import java.nio.file.Path`, then `Path("x")`) is that
    // type; a top-level function of the same name that no import brings in (`kotlin.io.path.Path`) is invisible to
    // Kotlin and is no target. The same rule [computeCallee] applies to the callee itself.
    if ((q == null || q.selectorExpression !== call) && name.firstOrNull()?.isUpperCase() == true && out.isNotEmpty() &&
        service.resolveTypeName(name, fileContext, enclosingClassFqn(call.textRange.startOffset))?.let { service.isKnownType(it) } == true
    ) {
        out.removeAll { f -> f.packageName != null && !f.isExtension && !topLevelInScope(f, fileContext) }
    }
    // A fully-qualified top-level call `kotlin.math.abs(x)` / `kotlinx.coroutines.flow.flowOf(…)`: the receiver is
    // a PACKAGE (not a value/type — `memberReceiverOf` was null and the in-scope lookup above missed, since the
    // name isn't imported). Resolve the top-level function by its package FQN. Purely additive: only fires for a
    // qualified call that otherwise resolves to nothing, and matches only a real function in that exact package.
    if (q != null && q.selectorExpression === call) {
        packageDottedPath(q.receiverExpression)?.let { pkg ->
            out += service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD && it.packageName == pkg }
            // `pkg.Foo(…)` naming a class there too: its constructors compete with a same-named factory.
            val fqn = "$pkg.$name"
            if (name.firstOrNull()?.isUpperCase() == true && service.isKnownType(fqn)) {
                out += service.constructorsOf(fqn)
                service.sourceClass(fqn)?.constructors?.forEach { rc -> out += sourceCtorSymbol(rc, fqn) }
            }
        }
    }
    // A capitalized callee is a constructor call (`Foo(…)`): its parameters come from the type's constructors.
    // The enclosing class is passed so a NESTED type reached by its simple name from inside the enclosing body
    // yields its constructors (`Level(21, "x")` inside `object Api { data class Level(…) }`).
    if (name.firstOrNull()?.isUpperCase() == true) {
        // A project `typealias` constructed by its own name (`DiskFile(path)`).
        service.typeAliasTargetFqn(name, fileContext)?.let { fqn ->
            out += service.constructorsOf(fqn)
            service.sourceClass(fqn)?.constructors?.forEach { rc -> out += sourceCtorSymbol(rc, fqn) }
        }
        service.resolveTypeName(name, fileContext, enclosingClassFqn(call.textRange.startOffset))?.let { fqn ->
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

/** The dotted name path of a pure package/qualifier receiver (`kotlin.math` → "kotlin.math"), or null when [expr]
 *  is anything other than a chain of name references (a call, indexing, literal, `this`, …). Used to resolve a
 *  fully-qualified top-level call by its package. */
private fun packageDottedPath(expr: KtExpression): String? = when (expr) {
    is KtNameReferenceExpression -> expr.getReferencedName()
    is KtQualifiedExpression -> {
        val recv = packageDottedPath(expr.receiverExpression) ?: return null
        val sel = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        "$recv.$sel"
    }
    else -> null
}

/**
 * The CONSTRUCTOR a capitalized `Foo(…)` call targets, chosen by arity — the callee [resolveCalleeFunction]
 * never returns, since it resolves FUNCTIONS only and a constructor is not one. This is what lets a lambda
 * ARGUMENT of a constructor be typed from the parameter it fills: `EditorPainter(paint = { … })` gets the
 * receiver and parameters of `paint: DrawScope.(EditorPaintContext) -> Unit` exactly as a function's lambda
 * argument does — without it the lambda has no receiver, and every `DrawScope` member called in the body was
 * reported unresolved (the code compiles; the editor was wrong).
 *
 * Only ever consulted where a function callee didn't resolve, and only for a name [constructorTypeFqn]
 * accepts (a KNOWN type, not shadowed by a local), so it cannot make an unresolved call resolve — it supplies
 * a parameter shape the lambda would otherwise go without.
 */
internal fun KotlinResolver.constructorCallee(call: KtCallExpression): KotlinSymbol? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val parent = call.parent
    // A bare `Foo(…)`, or the qualified `pkg.Foo(…)` / `Outer.Inner(…)` spelling where the call is the selector.
    val name = if (parent is KtQualifiedExpression && parent.selectorExpression === call)
        parent.receiverExpression.text + "." + callee.getReferencedName()
    else callee.getReferencedName()
    val fqn = constructorTypeFqn(name, callee.textRange.startOffset) ?: return null
    val ctors = service.constructorsOf(fqn) +
        service.sourceClass(fqn)?.constructors.orEmpty().map { sourceCtorSymbol(it, fqn) }
    if (ctors.isEmpty()) return null
    val argCount = call.valueArguments.size
    // The same tiering [computeCallee] applies to functions: exact arity first, then the narrowest constructor
    // with MORE parameters (the caller omitted defaulted ones — `EditorPainter(id, paint)` against its five),
    // then the widest with fewer (a vararg tail absorbing the rest).
    return ctors.firstOrNull { it.paramTypes.size == argCount }
        ?: ctors.filter { it.paramTypes.size > argCount }.minByOrNull { it.paramTypes.size }
        ?: ctors.maxByOrNull { it.paramTypes.size }
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
 *  synthetic bytecode names (`p0`/`p1`) dropped. A Java callee offers none: Kotlin rejects named arguments
 *  for it, even though its parameter names are known (debug info, sources) for signature help. */
fun KotlinResolver.callParameters(call: KtCallExpression): List<ParamInfo> {
    val out = LinkedHashMap<String, ParamInfo>()
    for (s in callTargets(call)) {
        if (isJavaCallable(s)) continue
        s.paramNames.forEachIndexed { i, n ->
            // `p0`-style names are stripped Java/ASM ones; a Kotlin SOURCE parameter may genuinely be `p1`.
            if (n.isNotEmpty() && (s.origin.fromSource || !isSyntheticParamName(n))) {
                out.getOrPut(n) { ParamInfo(n, s.paramTypes.getOrNull(i) as? KotlinType) }
            }
        }
    }
    return out.values.toList()
}

/** The value parameters of the constructor a DELEGATION call targets — for named-argument completion the
 *  [callParameters] (KtCallExpression) path can't reach. A supertype constructor call in a class header
 *  (`class X : Base(<caret>)`, [KtSuperTypeCallEntry]) resolves the named supertype; a secondary-constructor
 *  delegation ([KtConstructorDelegationCall]) resolves the enclosing class for `this(<caret>)` and its
 *  superclass for `super(<caret>)` (unioning across supertypes — an interface contributes no constructor
 *  params, so only the real superclass appears, without any class-vs-interface discrimination). */
fun KotlinResolver.delegationCallParameters(marker: KtElement?): List<ParamInfo> {
    marker ?: return emptyList()
    marker.getParentOfType<KtSuperTypeCallEntry>(strict = true)?.let { entry ->
        val text = entry.typeReference?.text ?: return emptyList()
        val fqn = service.resolveTypeName(text.substringBefore('<').trim(), fileContext) ?: return emptyList()
        return constructorParams(fqn)
    }
    marker.getParentOfType<KtConstructorDelegationCall>(strict = true)?.let { call ->
        val cls = call.getParentOfType<KtClassOrObject>(strict = true) ?: return emptyList()
        if (call.isCallToThis) {
            return cls.fqName?.asString()?.let { constructorParams(it) } ?: emptyList()
        }
        val out = LinkedHashMap<String, ParamInfo>()
        for (e in cls.superTypeListEntries) {
            val text = e.typeReference?.text ?: continue
            val fqn = service.resolveTypeName(text.substringBefore('<').trim(), fileContext) ?: continue
            for (p in constructorParams(fqn)) out.getOrPut(p.name) { p }
        }
        return out.values.toList()
    }
    return emptyList()
}

/** The distinct value parameters across a type [fqn]'s constructors — binary via [KotlinSymbolService.
 *  constructorsOf], source via its [dev.ide.lang.kotlin.symbols.RawClass] constructors (incl. the primary) —
 *  synthetic `p0` names dropped. Shared by delegation-call parameter completion. */
private fun KotlinResolver.constructorParams(fqn: String): List<ParamInfo> {
    val out = LinkedHashMap<String, ParamInfo>()
    fun add(symbols: List<KotlinSymbol>) {
        for (s in symbols) if (!isJavaCallable(s)) s.paramNames.forEachIndexed { i, n ->
            // `p0`-style names are stripped Java/ASM ones; a Kotlin SOURCE parameter may genuinely be `p1`.
            if (n.isNotEmpty() && (s.origin.fromSource || !isSyntheticParamName(n))) {
                out.getOrPut(n) { ParamInfo(n, s.paramTypes.getOrNull(i) as? KotlinType) }
            }
        }
    }
    add(service.constructorsOf(fqn))
    service.sourceClass(fqn)?.constructors?.let { ctors -> add(ctors.map { sourceCtorSymbol(it, fqn) }) }
    return out.values.toList()
}

/** A function or constructor declared in a Java class, which Kotlin calls with positional arguments only. */
internal fun KotlinResolver.isJavaCallable(s: KotlinSymbol): Boolean =
    s.declaringClassFqn?.let(service::isJavaType) == true

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
            // `p0`-style names are stripped Java/ASM ones; a Kotlin SOURCE parameter may genuinely be `p1`.
            if (n.isNotEmpty() && (s.origin.fromSource || !isSyntheticParamName(n))) {
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

/**
 * The `invoke` a bare call `name(…)` binds to when [name] is a VALUE declared nearer than any top-level
 * function: a local or parameter, or a property of an enclosing class (a constructor `val` included). Kotlin
 * resolves a call scope by scope from the innermost, and at each scope a value with an `invoke` is a candidate
 * like a function, so `class C(private val log: (String) -> Unit) { fun f() = log("x") }` calls the
 * property's `invoke(String)`. The editor only collected FUNCTIONS for a bare call, so the property was never a
 * candidate and the call bound to `kotlin.math.log(Double, Double)` instead, reporting "inferred type is
 * String but Double was expected" on a valid call.
 *
 * Null when no such value is nearer, or a FUNCTION of that name is declared in the same scope (a function
 * beats a property there), or the value has no `invoke` (the compiler then keeps looking further out), so the
 * caller resolves as before. An empty list when the value's type is unknown: the value still shadows the outer
 * functions, so binding to one of them would be a guess, and resolving to nothing is the conservative answer.
 */
internal fun KotlinResolver.nearerValueInvokeTargets(call: KtCallExpression, name: String): List<KotlinSymbol>? {
    val offset = call.textRange.startOffset
    val locals = localsAt(offset, name)
    val local = locals.firstOrNull()
    val isValue = when {
        local != null -> local.kind != SymbolKind.METHOD // the nearest local decides: a local fun is a function
        else -> innermostClassDeclaresValue(offset, name) ?: return null
    }
    if (!isValue) return null
    val nearest = valueInvokeTargets(typeOfName(name, offset), offset)
    // K2's tower: a value whose `invoke` cannot take these arguments does not end the search; the next value of
    // that name further out is tried (`val some = 10; some()` calls a top-level `some: String` through
    // `String.invoke`, an inner `x: (Int) -> Unit` gives way to an outer `x: (String) -> Unit` for `x("hello")`).
    // Only when no value fits is the nearest one the answer, so its own error is what gets reported.
    if (nearest == null || nearest.isEmpty() || nearest.none { isApplicable(it, call, null) }) {
        val outer = locals.drop(1).takeWhile { it.kind != SymbolKind.METHOD }.mapNotNull { it.type as? KotlinType } +
            service.topLevelByName(name).filter { it.kind == SymbolKind.FIELD && it.isExtension.not() }.mapNotNull { it.type as? KotlinType }
        for (t in outer) {
            val targets = valueInvokeTargets(t, offset) ?: continue
            if (targets.any { isApplicable(it, call, null) }) return targets
        }
    }
    return nearest
}

/** The `invoke` candidates a value of [type] is called through (null when it has none, empty when its type is
 *  unknown): its `invoke` members and extensions, else the shape of a function type. */
private fun KotlinResolver.valueInvokeTargets(type: KotlinType?, offset: Int): List<KotlinSymbol>? {
    if (type == null || type.isTypeParameter) return emptyList()
    service.membersNamed(type.qualifiedName, type.typeArguments, "invoke")
        .filter { it.kind == SymbolKind.METHOD && (!it.isExtension || extensionInScope(it)) }
        .takeIf { it.isNotEmpty() }
        ?.let { return it }
    // A function type the member lookup can't enumerate: its shape is the `invoke` signature. An extension
    // function type (`String.() -> Unit`) takes its receiver as the first argument of a plain call.
    val shape = service.functionalShape(type) ?: return null
    fun invoke(params: List<dev.ide.lang.resolve.TypeRef?>) = KotlinSymbol(
        "invoke", SymbolKind.METHOD, type = shape.returnType,
        paramTypes = params,
        paramHasDefault = List(params.size) { false },
    )
    if (!shape.isExtension) return listOf(invoke(shape.parameterTypes))
    // An extension function type takes its receiver as the first argument of a plain call (`f(x)`), or from an
    // implicit `this` in scope (`fun go(b: W.() -> Unit) { b() }` inside `W`, `fun <T> T.twice(f: T.() -> T) =
    // f()`), so both shapes are candidates whenever some implicit receiver exists.
    val explicit = invoke(listOf(shape.receiverType) + shape.parameterTypes)
    return if (implicitReceiversAt(offset).isEmpty()) listOf(explicit) else listOf(explicit, invoke(shape.parameterTypes))
}

/**
 * A receiver call's candidates: the receiver type's members and receiver-keyed extensions, then the member
 * extensions an implicit receiver brings into scope ([scopeExts]: `RowScope`'s `Modifier.alignByBaseline()`
 * inside `Row { }`). The classpath index ALSO keys a class's member extensions by receiver, so the same
 * signature declared in `ContextualFlowRowOverflowScopeImpl`, an experimental class, came first and reported
 * an opt-in error on a plain `Row`. An extension the scope redeclares with the identical signature is that
 * duplicate, so the scope's own declaration replaces it. Anything else keeps its place (a scope's `T.let`
 * bound to `ColumnScope` must not displace the receiver's own `let`).
 */
private fun kotlinCandidateOrder(members: List<KotlinSymbol>, scopeExts: List<KotlinSymbol>): List<KotlinSymbol> {
    if (scopeExts.isEmpty()) return members
    fun sig(s: KotlinSymbol) = s.receiverTypeFqn + "(" + s.paramTypes.joinToString(",") { it?.qualifiedName.orEmpty() } + ")"
    val scoped = scopeExts.mapTo(HashSet()) { sig(it) }
    return members.filterNot { it.isExtension && it.typeParameters.isEmpty() && sig(it) in scoped } + scopeExts
}

/**
 * `x.f()` where the receiver's type has no member FUNCTION `f` but a member PROPERTY `f` of function type
 * (`class Rule(val apply: () -> Unit)`; `rule.apply()`): Kotlin invokes the property, and a member property's
 * `invoke` outranks every extension, so the stdlib `T.apply(block)` never gets a look in. [members] is the
 * receiver's METHOD lookup, which also carries the extensions; null when the property route does not apply.
 */
internal fun KotlinResolver.memberPropertyInvokeTargets(
    members: List<KotlinSymbol>,
    receiverType: KotlinType,
    name: String,
): List<KotlinSymbol>? {
    if (members.any { !it.isExtension }) return null
    val prop = service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
        .firstOrNull { it.kind == SymbolKind.FIELD && !it.isExtension } ?: return null
    val shape = service.functionalShape(prop.type as? KotlinType ?: return null)?.takeIf { !it.isExtension } ?: return null
    return listOf(
        KotlinSymbol(
            "invoke", SymbolKind.METHOD, type = shape.returnType,
            paramTypes = shape.parameterTypes,
            paramHasDefault = List(shape.parameterTypes.size) { false },
        )
    )
}

/**
 * `x.f()` where the receiver has no function `f` but an EXTENSION property `f` of (plain) function type
 * (`val Int.b: () -> String`; `5.b()`): the property is read on `x` and its value invoked. K2 tries this before a
 * variable of extension function type called with `x` as its receiver ([receiverValueInvokeTargets]), so with both
 * `val b: Int.() -> Int` and `val Int.b: () -> String` in scope, `5.b()` is a String (KT-64891).
 */
internal fun KotlinResolver.extensionPropertyInvokeTargets(
    call: KtCallExpression,
    receiverType: KotlinType,
    name: String,
): List<KotlinSymbol> {
    // The extension properties of that name on the receiver: top-level ones, and member extensions an implicit
    // receiver brings into scope (`val <X> Property<LoadingValue<X>>.liveLoaded: X` inside an interface the
    // enclosing function extends), each bound to the receiver's arguments.
    val props = service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
        .filter { it.kind == SymbolKind.FIELD && it.isExtension } +
        scopeMemberExtensions(call.textRange.startOffset, receiverType, name, exactName = true)
            .filter { it.kind == SymbolKind.FIELD && it.name == name }
    // The most specific receiver wins, as for any extension (`C.attr` and `D.attr` on a `D`: `D.attr`): the
    // receiver's own type first, then its supertypes nearest first.
    val chain = listOf(receiverType.qualifiedName) + service.supertypesOf(receiverType.qualifiedName).mapNotNull { (it as? KotlinType)?.qualifiedName }
    fun rank(p: KotlinSymbol): Int = chain.indexOf(p.receiverTypeFqn).let { if (it < 0) Int.MAX_VALUE else it }
    for (prop in props.sortedBy(::rank)) {
        val value = prop.type as? KotlinType ?: continue
        // A value of plain function type is invoked directly.
        service.functionalShape(value)?.takeIf { !it.isExtension }?.let { shape ->
            return listOf(
                KotlinSymbol(
                    "invoke", SymbolKind.METHOD, type = shape.returnType,
                    paramTypes = shape.parameterTypes,
                    paramHasDefault = List(shape.parameterTypes.size) { false },
                )
            )
        }
        // Any other value through its `operator fun invoke`, a member or an extension
        // (`operator fun <T : AsyncModule, R> T.invoke(handler: T.() -> R)`), bound to the value's type.
        if (value.isTypeParameter) continue
        val invokes = service.membersNamed(value.qualifiedName, value.typeArguments, "invoke")
            .filter { it.kind == SymbolKind.METHOD && (!it.isExtension || extensionInScope(it)) }
        if (invokes.isNotEmpty()) return invokes
    }
    return emptyList()
}

/**
 * `s.f()` where `f` is a parameter, local or enclosing-class property of EXTENSION function type
 * (`f: String.() -> Int`, a builder's `block: S.() -> S`): the value is invoked with `s` as its receiver, so the
 * call's target is its `invoke` with the remaining parameters. Consulted only once the receiver has no member or
 * extension of that name, which is the order Kotlin resolves them in. Empty when [name] is not such a value.
 */
internal fun KotlinResolver.receiverValueInvokeTargets(
    call: KtCallExpression,
    name: String,
    receiverType: KotlinType?,
): List<KotlinSymbol> {
    val offset = call.textRange.startOffset
    val local = localsAt(offset, name).firstOrNull()
    // Nearest first: a local, then the innermost class that declares the name, then a top-level property
    // (`val foo: O.() -> Unit` called as `O.foo()`) when no top-level function shares its name.
    val isValue = if (local != null) local.kind != SymbolKind.METHOD else innermostClassDeclaresValue(offset, name)
        ?: service.topLevelByName(name).let { top -> top.any { it.kind == SymbolKind.FIELD } && top.none { it.kind == SymbolKind.METHOD } }
            .takeIf { it }
        // A property of an implicit receiver, a member extension one included (`val B.foo: C.() -> Unit` declared in
        // `A`, read inside `with(a) { with(b) { c.foo() } }`).
        ?: implicitReceiversAt(offset).any { recv ->
            service.membersNamed(recv.qualifiedName, recv.typeArguments, name).any { it.name == name && it.kind == SymbolKind.FIELD } ||
                mayBeMemberExtensionProperty(name) &&
                scopeMemberExtensions(offset, recv, name, exactName = true).any { it.name == name && it.kind == SymbolKind.FIELD }
        }
    if (!isValue) return emptyList()
    val type = typeOfName(name, offset) ?: return emptyList()
    val shape = service.functionalShape(type)?.takeIf { it.isExtension } ?: return emptyList()
    // The value's receiver must accept the explicit one (`s.f()` with `f: Int.() -> Unit` stays an error). A
    // type-parameter receiver on either side (`f: S.() -> S` on an `s: S`) is left to the checker's own leniency.
    val wanted = shape.receiverType as? KotlinType
    // A receiver type that names nothing (an unresolved class, a recursive alias) is an error type, which takes
    // any receiver: K2 reports the type once and not each call through it.
    if (wanted != null && receiverType != null && !wanted.isTypeParameter && !receiverType.isTypeParameter &&
        !isTypeParameterInScope(wanted.qualifiedName, offset) && service.isKnownType(wanted.qualifiedName) &&
        !wanted.isAssignableFrom(receiverType.withNullable(false))
    ) return emptyList()
    return listOf(
        KotlinSymbol(
            "invoke", SymbolKind.METHOD, type = shape.returnType,
            paramTypes = shape.parameterTypes,
            paramHasDefault = List(shape.parameterTypes.size) { false },
        )
    )
}

/** For the innermost enclosing class that declares a member named [name] (a function, a property, a
 *  constructor `val`/`var`, or an enum entry): true when that member is a VALUE and no same-named FUNCTION
 *  sits beside it, false when a function does. Null when no enclosing class declares the name. */
private fun KotlinResolver.innermostClassDeclaresValue(offset: Int, name: String): Boolean? {
    var node: KtElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            val hasFunction = node.declarations.any { it is KtNamedFunction && it.name == name }
            val hasValue = node.declarations.any {
                (it is KtProperty && it.name == name) || (it is KtEnumEntry && it.name == name)
            } || node.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }
            if (hasFunction) return false
            if (hasValue) return true
        }
        node = node.parent
    }
    return null
}

/**
 * The JVM statics named [name] that a TYPE receiver written in its Java spelling reaches on a class Kotlin maps
 * to a built-in: `java.lang.Integer.parseInt`, and also the bare `Integer.parseInt` / `Character.isDigit`,
 * whose simple names exist only as the Java classes (`Integer` is never `kotlin.Int`). A receiver spelled with
 * the Kotlin name (`String.valueOf`, `Long.parseLong`) names the built-in, which has no such statics, so it
 * stays empty and is reported as Kotlin reports it.
 */
internal fun KotlinResolver.mappedJavaStatics(receiver: KtExpression, receiverType: KotlinType, name: String): List<KotlinSymbol> {
    if (!isTypeReceiver(receiver)) return emptyList()
    val kotlinName = Builtins.kotlinTypeFor(receiverType.qualifiedName) ?: return emptyList()
    val written = receiver.text.trim()
    val javaSpelling = written == receiverType.qualifiedName ||
        written.substringAfterLast('.') != kotlinName.substringAfterLast('.')
    if (!javaSpelling) return emptyList()
    return service.mappedTypeStatics(receiverType.qualifiedName).filter { it.name == name }
}

/** The value a callable reference's target produces: a property's type (`P::age`) or a function's return type
 *  (`::len`, `String::length`). Null when the target or its type is unknown, or generic. */
internal fun KotlinResolver.callableReferenceResultType(ref: KtCallableReferenceExpression): KotlinType? {
    val target = runCatching { callableReferenceTarget(ref) }.getOrNull() ?: return null
    if (target.typeParameters.isNotEmpty()) return null
    return (target.type as? KotlinType)?.takeIf { !it.isTypeParameter }
}
